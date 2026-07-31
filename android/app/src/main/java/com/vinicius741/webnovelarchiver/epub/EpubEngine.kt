package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.EpubResult
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB generation and retention for downloaded story chapters. */
class EpubEngine(
    private val repository: AppRepository,
    private val network: NetworkClient,
) {
    private val storage = repository.storage

    private data class CoverAsset(
        val data: ByteArray,
        val href: String,
        val mediaType: String,
    )

    suspend fun generate(
        story: Story,
        chapters: List<Chapter>,
        config: EpubConfig,
        originalChapterNumbers: List<Int>? = null,
        progress: (String) -> Unit = {},
    ): List<EpubResult> =
        generateWithProgress(story, chapters, config, originalChapterNumbers) { update ->
            progress("Generating EPUB ${update.completed}/${update.total}...")
        }

    /** Typed progress entry point for callers that can render progress without parsing text. */
    suspend fun generateWithProgress(
        story: Story,
        chapters: List<Chapter>,
        config: EpubConfig,
        originalChapterNumbers: List<Int>? = null,
        progress: (EpubProgress) -> Unit = {},
    ): List<EpubResult> =
        withContext(Dispatchers.IO) {
            val available = chapters.filter { it.content != null || storage.readChapter(it) != null }
            if (available.isEmpty()) error("No downloaded chapters available")
            val chaptersPerFile =
                config.maxChaptersPerEpub.coerceIn(
                    EpubConfig.MAX_CHAPTERS_PER_EPUB_MIN,
                    EpubConfig.MAX_CHAPTERS_PER_EPUB_MAX,
                )
            val chaptersOnly = config.chaptersOnly
            val chunks = available.chunked(chaptersPerFile)
            val results = mutableListOf<EpubResult>()
            val chapterNumberById =
                chapters
                    .mapIndexed { index, chapter ->
                        chapter.id to (originalChapterNumbers?.getOrNull(index) ?: (index + 1))
                    }.toMap()
            chunks.forEachIndexed { index, chunk ->
                progress(EpubProgress(completed = index + 1, total = chunks.size))
                val start = chapterNumberById[chunk.first().id] ?: (chapters.indexOf(chunk.first()) + 1)
                val end = chapterNumberById[chunk.last().id] ?: (chapters.indexOf(chunk.last()) + 1)
                val filename = EpubFilename.forRange(story.title, start, end)
                // Fetch the cover (suspend) before streaming the EPUB to its final file via a
                // temp+rename, keeping one chapter's XHTML resident at a time. When chaptersOnly
                // is set we skip the fetch entirely — no network round-trip, and no failed-fetch
                // risk on a missing cover URL.
                val coverAsset = if (chaptersOnly) null else story.coverUrl?.let { fetchCover(it) }
                val file =
                    storage.saveEpubStreamed(story.id, filename) { out ->
                        writeEpub(ZipOutputStream(out), story, chunk, coverAsset, chaptersOnly)
                    }
                results.add(EpubResult(file.absolutePath, filename, start, end))
            }
            val committed = repository.markEpubGenerated(story.id, results.map { it.uri })
            check(committed != null) {
                "Story was removed while EPUB generation was running"
            }
            applyRetention(story.id, committed.epubPaths.orEmpty().toSet())
            results
        }

    private fun applyRetention(
        storyId: String,
        referencedPaths: Set<String>,
    ) {
        val entries =
            storage.listEpubs(storyId).map { file ->
                EpubStorageEntry(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath), file.length(), file.lastModified())
            }
        val canonicalReferences = referencedPaths.mapTo(mutableSetOf()) { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        val plan = EpubRetentionPolicy.plan(entries, canonicalReferences)
        plan.delete.forEach { storage.deleteEpubFile(storyId, it.path) }
    }

    /**
     * Writes the full EPUB 2.0 structure into [zip]. Streamed entry-by-entry so only one chapter's
     * XHTML is resident at a time. Non-suspend — the cover is fetched before streaming.
     *
     * When [chaptersOnly] is true, all front matter is omitted: the cover image, cover page,
     * description/tags page, and human-readable TOC are skipped here, and `opf`/`ncx` drop their
     * references to keep the package consistent.
     */
    private fun writeEpub(
        zip: ZipOutputStream,
        story: Story,
        chapters: List<Chapter>,
        coverAsset: CoverAsset?,
        chaptersOnly: Boolean,
    ) {
        ArchiveUtils.putStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray())
        entry(
            zip,
            "META-INF/container.xml",
            """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        )
        entry(zip, "OEBPS/style.css", EpubContent.css())
        coverAsset?.let { cover ->
            zip.putNextEntry(ZipEntry("OEBPS/${cover.href}"))
            zip.write(cover.data)
            zip.closeEntry()
        }
        if (!chaptersOnly) {
            entry(zip, "OEBPS/cover.xhtml", EpubContent.cover(story, coverAsset?.href))
            entry(zip, "OEBPS/details.xhtml", EpubContent.details(story))
            entry(zip, "OEBPS/toc.xhtml", EpubContent.tableOfContents(chapters))
        }
        chapters.forEachIndexed { i, chapter ->
            entry(zip, "OEBPS/chapter_${i + 1}.xhtml", EpubContent.chapter(chapter, storage.readChapter(chapter) ?: ""))
        }
        entry(
            zip,
            "OEBPS/content.opf",
            EpubMetadata.opf(
                story,
                chapters,
                coverAsset?.let {
                    EpubCoverMetadata(it.href, it.mediaType)
                },
                chaptersOnly,
            ),
        )
        entry(zip, "OEBPS/toc.ncx", EpubMetadata.ncx(story, chapters, chaptersOnly))
        zip.close()
    }

    private suspend fun fetchCover(url: String): CoverAsset? =
        runCatching {
            // Route cover downloads through the shared client with a size cap instead of a raw URL
            // connection, which would bypass the app's timeout and request policies.
            val data = network.fetchBytes(url) ?: return@runCatching null
            val mediaType = getCoverMediaType(url)
            val extension = getCoverExtension(url, mediaType)
            CoverAsset(data, "images/cover.$extension", mediaType)
        }.getOrNull()

    private fun entry(
        zip: ZipOutputStream,
        path: String,
        text: String,
    ) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    private fun getCoverMediaType(coverUrl: String): String {
        val extension = getExtensionFromUrl(coverUrl)
        return coverMediaTypes[extension] ?: "image/jpeg"
    }

    private fun getCoverExtension(
        coverUrl: String,
        mediaType: String,
    ): String {
        val extension = getExtensionFromUrl(coverUrl)
        if (extension != null && coverMediaTypes[extension] == mediaType) return extension
        val matched = coverMediaTypes.entries.firstOrNull { it.value == mediaType }?.key
        return if (matched == "jpeg") "jpg" else matched ?: "jpg"
    }

    private fun getExtensionFromUrl(url: String): String? =
        runCatching {
            URL(url)
                .path
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotBlank() }
        }.getOrElse {
            url
                .substringBefore('?')
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotBlank() }
        }

    private val coverMediaTypes =
        mapOf(
            "gif" to "image/gif",
            "jpeg" to "image/jpeg",
            "jpg" to "image/jpeg",
            "png" to "image/png",
            "svg" to "image/svg+xml",
            "webp" to "image/webp",
        )
}
