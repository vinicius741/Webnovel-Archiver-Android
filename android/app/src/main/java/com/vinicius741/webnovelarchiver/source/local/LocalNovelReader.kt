package com.vinicius741.webnovelarchiver.source.local

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.publicationStatusFromSourceText
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** The kinds of book file a local novel can hold. */
enum class LocalBookFormat(
    val label: String,
    val extensions: Set<String>,
) {
    EPUB("EPUB", setOf("epub")),
    PDF("PDF", setOf("pdf")),
    TEXT("TXT", setOf("txt", "text")),
    MARKDOWN("Markdown", setOf("md", "markdown")),
    HTML("HTML", setOf("html", "htm", "xhtml")),
    ;

    companion object {
        fun of(fileName: String): LocalBookFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/** One novel the local folder offers: a subfolder, or a book file directly in the folder. */
data class LocalEntry(
    val name: String,
    val isFolder: Boolean,
    val storyUrl: String,
    val storyId: String,
    /** Book files the novel is made of. */
    val books: Int,
    val formats: List<LocalBookFormat>,
)

data class LocalNovel(
    val metadata: NovelMetadata,
    val chapters: List<ChapterInfo>,
)

/**
 * Turns the local folder into novels and chapters. A subfolder is a novel made of its book files
 * in natural name order (chapter 2 before chapter 10); a book file directly in the folder is a
 * novel of its own. Optional extras in a subfolder: a `cover.jpg`/`.png`/`.webp` image and a
 * Mihon-style `details.json` (title, author, description, genre, status).
 *
 * Parsed books are kept for a few reads, so downloading a book's chapters parses it once.
 */
class LocalNovelReader(
    private val files: LocalLibraryFiles,
    private val pdf: PdfPages,
) {
    private val parsed =
        object : LinkedHashMap<String, ParsedBook>(PARSED_BOOKS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedBook>?): Boolean = size > PARSED_BOOKS
        }

    private val listings = HashMap<String, Pair<Long, List<LocalDocument>>>()

    fun folderName(): String? = files.folderName()

    fun setFolder(treeUri: String) {
        synchronized(this) { parsed.clear() }
        synchronized(listings) { listings.clear() }
        files.setFolder(treeUri)
    }

    /** Every novel the local folder offers, in natural name order. */
    fun entries(): List<LocalEntry> =
        files
            .listRoot()
            .filterNot { it.name.startsWith('.') }
            .mapNotNull { document ->
                val books =
                    if (document.isDirectory) {
                        files.listChildren(document).filter {
                            !it.isDirectory && !it.name.startsWith('.') && LocalBookFormat.of(it.name) != null
                        }
                    } else {
                        listOf(document).filter { LocalBookFormat.of(it.name) != null }
                    }
                // A folder of images or of nested folders holds no novel to add.
                if (books.isEmpty()) return@mapNotNull null
                LocalEntry(
                    name = document.name,
                    isFolder = document.isDirectory,
                    storyUrl = LocalSourceUrls.story(document.name),
                    storyId = LocalSourceUrls.storyId(document.name),
                    books = books.size,
                    formats = books.mapNotNull { LocalBookFormat.of(it.name) }.distinct(),
                )
            }.sortedWith(compareBy(LocalNames.natural) { it.name })

    /** Reads a novel's metadata and chapter list, saving its cover. */
    fun novel(entryName: String): LocalNovel {
        val entry = files.listRoot().firstOrNull { it.name == entryName } ?: throw LocalEntryMissingException(entryName)
        val children = if (entry.isDirectory) files.listChildren(entry) else emptyList()
        val bookDocs =
            (if (entry.isDirectory) children.filter { !it.isDirectory } else listOf(entry))
                .filter { LocalBookFormat.of(it.name) != null && !it.name.startsWith('.') }
                .sortedWith(compareBy(LocalNames.natural) { it.name })
        val chapters = mutableListOf<ChapterInfo>()
        var epub: EpubMetadata? = null
        var cover = folderCover(children)
        var skipped: Exception? = null
        // One book at a time, so only the parse cache keeps book text alive during a rescan.
        bookDocs.forEach { document ->
            val book =
                readableBook(document)
                    .onFailure { skipped = skipped ?: it as? Exception }
                    .getOrNull() ?: return@forEach
            chapters += chapterInfos(entryName, entry.isDirectory, document, book, multiBook = bookDocs.size > 1)
            epub = epub ?: book.metadata
            if (cover == null) cover = runCatching { book.cover() }.getOrNull()
        }
        if (chapters.isEmpty()) {
            throw skipped
                ?: IllegalStateException(if (bookDocs.isEmpty()) "\"$entryName\" holds no book files." else "No text in \"$entryName\".")
        }
        val details = children.firstOrNull { it.name.equals(DETAILS_FILE, ignoreCase = true) }?.let(::details)
        val title =
            details?.title
                ?: if (entry.isDirectory) entryName else epub?.title ?: LocalTextChapters.titleFromFileName(entryName)
        val storyUrl = LocalSourceUrls.story(entryName)
        val metadata =
            NovelMetadata(
                title = title,
                author = details?.author ?: epub?.author ?: "Unknown Author",
                coverUrl = cover?.let { saveCover(LocalSourceUrls.storyId(entryName), it) },
                description = details?.description ?: epub?.description,
                tags = (details?.genres ?: epub?.subjects)?.takeIf { it.isNotEmpty() }?.toMutableList(),
                canonicalUrl = storyUrl,
                // A book on the phone is finished unless details.json says otherwise; "unknown" would
                // turn Outdated a week after the last sync.
                publicationStatus = details?.status ?: PublicationStatus.completed,
            )
        return LocalNovel(metadata, chapters)
    }

    /**
     * A book, or the reason this app cannot read it (a scanned PDF, a broken EPUB), which leaves the
     * folder's other books readable. A failure to read the folder itself is thrown: a sync that went
     * on without the book would mark its chapters removed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readableBook(document: LocalDocument): Result<ParsedBook> =
        try {
            Result.success(book(document))
        } catch (error: ZipException) {
            Result.failure(error)
        } catch (error: IOException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Result.failure(error)
        }

    /** Reader HTML of one chapter. */
    fun chapterHtml(address: LocalAddress): String {
        val entry = recentRoot().firstOrNull { it.name == address.entry } ?: throw LocalEntryMissingException(address.entry)
        val document =
            if (address.file != null) {
                if (!entry.isDirectory) throw LocalEntryMissingException(address.file)
                recentChildren(entry).firstOrNull { it.name == address.file } ?: throw LocalEntryMissingException(address.file)
            } else {
                entry
            }
        val book = book(document)
        val chapter =
            if (address.part == null) {
                book.chapters.firstOrNull() ?: throw LocalEntryMissingException(address.file ?: address.entry)
            } else {
                book.chapters.firstOrNull { it.key == address.part }
                    ?: throw LocalEntryMissingException("${address.file ?: address.entry} (${address.part})")
            }
        return chapter.html()
    }

    private fun chapterInfos(
        entryName: String,
        inFolder: Boolean,
        document: LocalDocument,
        book: ParsedBook,
        multiBook: Boolean,
    ): List<ChapterInfo> {
        val bookTitle = book.metadata?.title ?: LocalTextChapters.titleFromFileName(document.name)
        return book.chapters.map { chapter ->
            val base =
                chapter.title
                    ?: if (book.chapters.size == 1) LocalTextChapters.titleFromFileName(document.name) else "Opening"
            val title = if (multiBook && book.chapters.size > 1) "$bookTitle: $base" else base
            // A file that is one chapter needs no part; a book file at the top always names one, so
            // its chapter URL never equals the story URL.
            val part = if (inFolder && book.chapters.size == 1) null else chapter.key
            val address = LocalAddress(entryName, document.name.takeIf { inFolder }, part)
            ChapterInfo(
                id = LocalSourceUrls.chapterId(address),
                title = title,
                url = LocalSourceUrls.chapter(address),
                chapterNumber = null,
            )
        }
    }

    @Synchronized
    private fun book(document: LocalDocument): ParsedBook {
        val key = "${document.id}|${document.size}|${document.lastModified}"
        parsed[key]?.let { return it }
        val book =
            when (LocalBookFormat.of(document.name)) {
                LocalBookFormat.EPUB ->
                    try {
                        epub { files.cachedCopy(document) }
                    } catch (_: ZipException) {
                        error("\"${document.name}\" is damaged or is not an EPUB.")
                    }
                LocalBookFormat.PDF -> pdfBook { files.cachedCopy(document) }
                LocalBookFormat.TEXT -> textBook(LocalTextChapters.decode(files.readBytes(document)), markdown = false)
                LocalBookFormat.MARKDOWN -> textBook(LocalTextChapters.decode(files.readBytes(document)), markdown = true)
                LocalBookFormat.HTML -> htmlBook(files.readBytes(document))
                null -> error("Unsupported file: ${document.name}")
            }
        parsed[key] = book
        return book
    }

    /** [copy] is asked again for each chapter: the cached copy may be evicted and recreated meanwhile. */
    private fun epub(copy: () -> File): ParsedBook =
        ZipFile(copy()).use { zip ->
            val read = zipReader(zip)
            val book = EpubParsing.readPackage(read)
            val plans = EpubParsing.planChapters(book) { path -> EpubParsing.docInfo(read(path)?.toString(Charsets.UTF_8).orEmpty()) }
            val cover =
                book.coverPath?.let { path -> read(path)?.let { bytes -> CoverImage(bytes, path.substringAfterLast('.', "jpg")) } }
            ParsedBook(
                chapters =
                    plans.map { plan ->
                        BookChapter(plan.title, plan.key) {
                            ZipFile(copy()).use { reopened ->
                                val reread = zipReader(reopened)
                                EpubParsing.chapterHtml(plan) { path -> reread(path)?.toString(Charsets.UTF_8) }
                            }
                        }
                    },
                metadata = book.metadata,
                cover = { cover },
            )
        }

    private fun pdfBook(copy: () -> File): ParsedBook {
        if (!pdf.supported) throw UnsupportedOperationException("Reading PDF text needs Android 15 or newer.")
        val text = PdfTextReflow.reflow(pdf.text(copy()))
        check(text.isNotBlank()) { "This PDF has no text to read. Scanned pages are images, not text." }
        return textBook(text, markdown = false, cover = { pdf.firstPageImage(copy())?.let { CoverImage(it, "jpg") } })
    }

    private fun textBook(
        text: String,
        markdown: Boolean,
        cover: () -> CoverImage? = { null },
    ): ParsedBook =
        ParsedBook(
            chapters =
                LocalTextChapters.split(text, markdown).map { chapter ->
                    BookChapter(chapter.title, chapter.key) { LocalTextChapters.toHtml(chapter.body, markdown) }
                },
            metadata = null,
            cover = cover,
        )

    /** An HTML file names its encoding in a `<meta charset>`; without one it is decoded like a text file. */
    private fun htmlBook(bytes: ByteArray): ParsedBook {
        val head = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charsets.ISO_8859_1)
        val html =
            if (head.contains("charset", ignoreCase = true)) {
                Jsoup.parse(ByteArrayInputStream(bytes), null, "").outerHtml()
            } else {
                LocalTextChapters.decode(bytes)
            }
        val doc = Jsoup.parse(html)
        val title = (doc.body().selectFirst("h1, h2")?.text() ?: doc.title()).trim().takeIf { it.isNotEmpty() }
        val body = EpubParsing.chapterHtml(EpubChapterPlan(title.orEmpty(), "all", listOf(EpubSegment("")))) { html }
        return ParsedBook(listOf(BookChapter(title, "all") { body }), metadata = null, cover = { null })
    }

    /** A cover image in the novel's folder (cover, folder or poster .jpg/.png/.webp/.gif). */
    private fun folderCover(children: List<LocalDocument>): CoverImage? =
        children
            .filter { !it.isDirectory && it.name.substringBeforeLast('.').lowercase() in COVER_NAMES }
            .firstOrNull { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            ?.let { runCatching { CoverImage(files.readBytes(it), it.name.substringAfterLast('.')) }.getOrNull() }

    private fun saveCover(
        storyId: String,
        image: CoverImage,
    ): String? {
        val extension = image.extension.lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
        return runCatching { files.saveCover(storyId, image.bytes, extension) }.getOrNull()
    }

    /**
     * Folder listings for chapter reads, reused for a few seconds: reading a novel's chapters one by
     * one would otherwise list the folder again for every chapter. Scans always list afresh.
     */
    private fun recentRoot(): List<LocalDocument> = recent(ROOT_LISTING) { files.listRoot() }

    private fun recentChildren(directory: LocalDocument): List<LocalDocument> = recent(directory.id) { files.listChildren(directory) }

    private fun recent(
        key: String,
        list: () -> List<LocalDocument>,
    ): List<LocalDocument> {
        val now = System.nanoTime()
        synchronized(listings) {
            listings[key]?.takeIf { now - it.first < LISTING_TTL_NANOS }?.let { return it.second }
        }
        return list().also { fresh -> synchronized(listings) { listings[key] = now to fresh } }
    }

    private fun details(document: LocalDocument): LocalDetails? =
        runCatching {
            val json = JsonParser.parseString(LocalTextChapters.decode(files.readBytes(document))).asJsonObject
            LocalDetails(
                title = json.text("title"),
                author = json.text("author") ?: json.text("artist"),
                description = json.text("description"),
                genres =
                    json
                        .get("genre")
                        ?.let { genre ->
                            if (genre.isJsonArray) genre.asJsonArray.mapNotNull { it.textOrNull() } else genre.textOrNull()?.split(',')
                        }?.map(String::trim)
                        ?.filter(String::isNotEmpty),
                status = json.text("status")?.let(::status),
            )
        }.getOrNull()

    private fun status(value: String): PublicationStatus =
        when (value.trim()) {
            // Mihon's numeric codes.
            "1" -> PublicationStatus.ongoing
            "2", "4" -> PublicationStatus.completed
            "5", "6" -> PublicationStatus.hiatus
            else -> publicationStatusFromSourceText(value) ?: PublicationStatus.unknown
        }

    private class ParsedBook(
        val chapters: List<BookChapter>,
        val metadata: EpubMetadata?,
        val cover: () -> CoverImage?,
    )

    private class BookChapter(
        val title: String?,
        val key: String,
        val html: () -> String,
    )

    private class CoverImage(
        val bytes: ByteArray,
        val extension: String,
    )

    private data class LocalDetails(
        val title: String?,
        val author: String?,
        val description: String?,
        val genres: List<String>?,
        val status: PublicationStatus?,
    )

    private companion object {
        const val PARSED_BOOKS = 4
        const val LOAD_FACTOR = 0.75f
        const val DETAILS_FILE = "details.json"
        const val CHARSET_SNIFF_BYTES = 1024
        const val ROOT_LISTING = "\u0000root"
        const val LISTING_TTL_NANOS = 10_000_000_000L
        val COVER_NAMES = setOf("cover", "folder", "poster")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

        /** Zip entries by path, falling back to a case-insensitive match (some EPUBs disagree with their manifest). */
        fun zipReader(zip: ZipFile): (String) -> ByteArray? {
            val byLowercase by lazy { zip.entries().asSequence().associateBy { it.name.lowercase() } }
            return { path ->
                val entry = zip.getEntry(path) ?: byLowercase[path.lowercase()]
                entry?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
            }
        }

        fun JsonObject.text(name: String): String? = get(name)?.textOrNull()

        fun JsonElement.textOrNull(): String? = takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Natural order for file and folder names: "Chapter 2" before "Chapter 10" and "12" before "12.5", case ignored. */
object LocalNames {
    private val chunk = Regex("\\d+|\\D+")
    private val extension = Regex("\\.[A-Za-z][A-Za-z0-9]{0,4}$")

    val natural: Comparator<String> =
        Comparator { left, right ->
            // Names compare without their extension, so "12.txt" does not sort after "12.5.txt".
            compareChunks(left.replace(extension, ""), right.replace(extension, "")).takeIf { it != 0 } ?: left.compareTo(right)
        }

    private fun compareChunks(
        left: String,
        right: String,
    ): Int {
        val a = chunk.findAll(left.lowercase()).map { it.value }.toList()
        val b = chunk.findAll(right.lowercase()).map { it.value }.toList()
        a.zip(b).forEach { (x, y) ->
            val compared =
                if (x[0].isDigit() && y[0].isDigit()) {
                    compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
                } else {
                    x.compareTo(y)
                }
            if (compared != 0) return compared
        }
        return a.size.compareTo(b.size)
    }
}
