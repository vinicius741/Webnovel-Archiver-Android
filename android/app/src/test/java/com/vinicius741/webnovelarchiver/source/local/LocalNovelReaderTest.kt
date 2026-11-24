package com.vinicius741.webnovelarchiver.source.local

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalNovelReaderTest {
    @get:Rule val temp = TemporaryFolder()

    /** An in-memory folder: a path with a '/' is inside a subfolder. */
    private inner class FakeFiles(
        val files: MutableMap<String, ByteArray>,
    ) : LocalLibraryFiles {
        val savedCovers = mutableListOf<String>()

        override fun setFolder(treeUri: String) = Unit

        override fun folderName() = "Books"

        override fun listRoot(): List<LocalDocument> =
            files.keys
                .map { it.substringBefore('/') to ('/' in it) }
                .distinct()
                .map { (name, folder) -> LocalDocument(name, name, folder, files[name]?.size?.toLong() ?: 0, 1) }

        override fun listChildren(directory: LocalDocument): List<LocalDocument> =
            files
                .filterKeys { it.startsWith("${directory.id}/") }
                .map { (path, bytes) -> LocalDocument(path, path.substringAfter('/'), false, bytes.size.toLong(), modified[path] ?: 1) }

        val modified = mutableMapOf<String, Long>()
        val unreadable = mutableSetOf<String>()

        override fun readBytes(document: LocalDocument): ByteArray {
            if (document.id in unreadable) throw java.io.IOException("read failed")
            return files.getValue(document.id)
        }

        override fun cachedCopy(document: LocalDocument): File = temp.newFile().apply { writeBytes(readBytes(document)) }

        override fun saveCover(
            storyId: String,
            bytes: ByteArray,
            extension: String,
        ) = "file:///covers/$storyId.$extension".also { savedCovers += String(bytes) }
    }

    private val noPdf =
        object : PdfPages {
            override val supported = true

            override fun text(file: File) = listOf("Chapter 1\nPdf one is a page of text long enough to count.\n\nChapter 2\nPdf two.")

            override fun firstPageImage(file: File) = "PAGE".toByteArray()

            override fun recognizer(file: File): PdfPageRecognizer = ScanRecognizer(emptyList())
        }

    /** A scan whose page [i] shows [pages][i], one line per text line. Counts the pages it recognized. */
    private class ScanRecognizer(
        val pages: List<String>,
    ) : PdfPageRecognizer {
        val recognized = mutableListOf<Int>()

        override val pageCount get() = pages.size

        override fun recognize(index: Int): List<OcrLine> {
            recognized += index
            return pages[index].lines().mapIndexed { line, text -> OcrLine(text, 100, 100 + line * 40, 900, 130 + line * 40) }
        }

        override fun close() = Unit
    }

    /** A PDF of scanned pages: no text layer, [recognizer] reads the images. */
    private fun scannedPdf(
        recognizer: ScanRecognizer,
        textLayer: Boolean = true,
    ) = object : PdfPages by noPdf {
        override val supported = textLayer

        override fun text(file: File) = recognizer.pages.map { "" }

        override fun recognizer(file: File): PdfPageRecognizer = recognizer
    }

    private val ocrDir by lazy { temp.newFolder("ocr") }

    private fun reader(pdf: PdfPages) = LocalNovelReader(files, pdf, PdfOcrCache(ocrDir))

    private val files =
        FakeFiles(
            mutableMapOf(
                "Saga/Chapter 10.txt" to "Chapter 10\n\nTen.".toByteArray(),
                "Saga/Chapter 2.txt" to "Two.".toByteArray(),
                "Saga/cover.jpg" to "JPG".toByteArray(),
                "Saga/notes.docx" to "skip".toByteArray(),
                "Saga/details.json" to """{"title":"The Saga","author":"Me","genre":"Drama, Quiet","status":"2"}""".toByteArray(),
                "Small Book.epub" to EpubFixtures.zip(),
                "Scan.pdf" to "pdf".toByteArray(),
                "readme.docx" to "skip".toByteArray(),
                "Art Only/cover.png" to "PNG".toByteArray(),
                "Mac Copy/._Book.epub" to "junk".toByteArray(),
            ),
        )
    private val reader by lazy { reader(noPdf) }

    @Test
    fun listsFoldersAndBookFilesInNaturalOrder() {
        val entries = reader.entries()

        assertEquals(listOf("Saga", "Scan.pdf", "Small Book.epub"), entries.map { it.name })
        val saga = entries.first()
        assertEquals(2, saga.books)
        assertEquals(listOf(LocalBookFormat.TEXT), saga.formats)
        assertEquals(LocalSourceUrls.story("Saga"), saga.storyUrl)
    }

    @Test
    fun aFolderNovelReadsItsFilesInOrderWithDetailsAndCover() {
        val novel = reader.novel("Saga")

        assertEquals("The Saga", novel.metadata.title)
        assertEquals("Me", novel.metadata.author)
        assertEquals(listOf("Drama", "Quiet"), novel.metadata.tags)
        assertEquals(PublicationStatus.completed, novel.metadata.publicationStatus)
        assertEquals(listOf("JPG"), files.savedCovers)
        assertEquals(listOf("Chapter 2", "Chapter 10"), novel.chapters.map { it.title })
        val first = requireNotNull(LocalSourceUrls.parse(novel.chapters[0].url))
        assertEquals(LocalAddress("Saga", "Chapter 2.txt"), first)
        assertEquals("<p>Two.</p>", reader.chapterHtml(first))
        assertEquals("<p>Ten.</p>", reader.chapterHtml(requireNotNull(LocalSourceUrls.parse(novel.chapters[1].url))))
    }

    @Test
    fun aBookFileIsANovelOfItsOwn() {
        val novel = reader.novel("Small Book.epub")

        assertEquals("The Small Book", novel.metadata.title)
        assertEquals("A. Writer", novel.metadata.author)
        assertEquals(listOf("PNG"), files.savedCovers)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), novel.chapters.map { it.title })
        val urls = novel.chapters.map { it.url }
        assertTrue(urls.none { it == LocalSourceUrls.story("Small Book.epub") })
        assertTrue(reader.chapterHtml(requireNotNull(LocalSourceUrls.parse(urls[1]))).contains("Two goes on."))

        val pdf = reader.novel("Scan.pdf")
        assertEquals(listOf("Chapter 1", "Chapter 2"), pdf.chapters.map { it.title })
        assertEquals("<p>Pdf two.</p>", reader.chapterHtml(requireNotNull(LocalSourceUrls.parse(pdf.chapters[1].url))))
    }

    @Test
    fun aRescanFindsNewFilesAndKeepsChapterIds() {
        val before = reader.novel("Saga").chapters
        files.files["Saga/Chapter 11.txt"] = "Eleven.".toByteArray()

        val after = reader.novel("Saga").chapters

        assertEquals(before.map { it.id }, after.take(2).map { it.id })
        assertEquals("Chapter 11", after.last().title)
    }

    @Test(expected = LocalEntryMissingException::class)
    fun aMissingNovelFailsClearly() {
        reader.novel("Gone")
    }

    @Test
    fun aScannedPdfIsRecognizedOnceWithProgress() {
        val scan = ScanRecognizer(listOf("Chapter 1\nScanned one.", "Chapter 2\nScanned two."))
        val statuses = mutableListOf<String>()

        val novel = reader(scannedPdf(scan)).novel("Scan.pdf") { statuses += it }

        assertEquals(listOf("Chapter 1", "Chapter 2"), novel.chapters.map { it.title })
        assertEquals(listOf("Recognizing text in \"Scan.pdf\": page 1 of 2", "Recognizing text in \"Scan.pdf\": page 2 of 2"), statuses)
        assertEquals(listOf(0, 1), scan.recognized)

        // A later rescan or chapter read (a new process: nothing parsed in memory) uses the saved text.
        val again = ScanRecognizer(listOf("", ""))
        val reread = reader(scannedPdf(again))
        assertEquals(novel.chapters.map { it.id }, reread.novel("Scan.pdf").chapters.map { it.id })
        assertEquals("<p>Scanned two.</p>", reread.chapterHtml(requireNotNull(LocalSourceUrls.parse(novel.chapters[1].url))))
        assertEquals(emptyList<Int>(), again.recognized)
    }

    @Test
    fun anInterruptedRecognitionResumesAtTheNextPage() {
        val scan = ScanRecognizer(listOf("Chapter 1\nOne.", "More of one.", "Chapter 2\nTwo."))
        val cancelling = reader(scannedPdf(scan))
        val error =
            runCatching {
                cancelling.novel("Scan.pdf") { status -> if (status.contains("page 2")) throw java.util.concurrent.CancellationException() }
            }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(listOf(0), scan.recognized)

        val resumed = ScanRecognizer(scan.pages)
        reader(scannedPdf(resumed)).novel("Scan.pdf")
        assertEquals(listOf(1, 2), resumed.recognized)
    }

    @Test
    fun aScansTextPagesAreReadAndItsImagePagesRecognized() {
        val scan = ScanRecognizer(listOf("Chapter 1\nScanned one.", "unused", "Scanned on.", "Chapter 2\nScanned two."))
        val pdf =
            object : PdfPages by noPdf {
                override fun text(file: File) = listOf("", "Searchable text on this one page, long enough to count as text.", "", "")

                override fun recognizer(file: File): PdfPageRecognizer = scan
            }

        val novel = reader(pdf).novel("Scan.pdf")

        assertEquals(listOf(0, 2, 3), scan.recognized)
        assertTrue(reader(pdf).chapterHtml(requireNotNull(LocalSourceUrls.parse(novel.chapters[0].url))).contains("Searchable text"))
    }

    @Test
    fun aFailedRecognitionFailsTheScanInsteadOfSkippingTheBook() {
        files.files["Saga/Volume 2.pdf"] = "pdf".toByteArray()
        val failing =
            object : PdfPages by noPdf {
                override fun text(file: File) = listOf("")

                override fun recognizer(file: File): PdfPageRecognizer =
                    object : PdfPageRecognizer by ScanRecognizer(listOf("")) {
                        override fun recognize(index: Int): List<OcrLine> = throw LocalSourceException("Text recognition failed on page 1")
                    }
            }

        // Going on without the book would mark its chapters removed from the source.
        assertTrue(runCatching { reader(failing).novel("Saga") }.exceptionOrNull() is LocalSourceException)
    }

    @Test
    fun oldPhonesRecognizeEveryPage() {
        val scan = ScanRecognizer(listOf("Chapter 1\nOld one.", "Chapter 2\nOld two."))

        val novel = reader(scannedPdf(scan, textLayer = false)).novel("Scan.pdf")

        assertEquals(listOf("Chapter 1", "Chapter 2"), novel.chapters.map { it.title })
        assertEquals(listOf(0, 1), scan.recognized)
        assertNull(reader(scannedPdf(scan, textLayer = false)).entries().firstOrNull { it.name == "readme.docx" })
    }

    @Test
    fun aTextPdfsBlankPagesAreNotRecognized() {
        val scan = ScanRecognizer(listOf("Map label", "Map label", "Map label"))
        val pdf =
            object : PdfPages by noPdf {
                override fun text(file: File) =
                    listOf(
                        "Chapter 1\nText one, a whole page of the book's own text, long enough to count.",
                        "",
                        "Chapter 2\nText two, another page of the book's own text, long enough to count.",
                    )

                override fun recognizer(file: File): PdfPageRecognizer = scan
            }

        assertEquals(listOf("Chapter 1", "Chapter 2"), reader(pdf).novel("Scan.pdf").chapters.map { it.title })
        assertEquals(emptyList<Int>(), scan.recognized)
    }

    @Test
    fun aFolderWithoutBooksIsNotOffered() {
        assertNull(reader.entries().firstOrNull { it.name == "Art Only" })
        assertNull(reader.entries().firstOrNull { it.name == "Mac Copy" })
    }

    @Test
    fun aBookFileIsCompletedUnlessDetailsSayOtherwise() {
        assertEquals(PublicationStatus.completed, reader.novel("Small Book.epub").metadata.publicationStatus)
    }

    @Test
    fun anUnreadableBookIsSkippedButAFailedReadFailsTheScan() {
        files.files["Saga/Chapter 5.pdf"] = "pdf".toByteArray()
        val noText =
            object : PdfPages by noPdf {
                override fun text(file: File) = listOf("")
            }
        val skipping = reader(noText)
        assertEquals(listOf("Chapter 2", "Chapter 10"), skipping.novel("Saga").chapters.map { it.title })

        files.unreadable += "Saga/Chapter 2.txt"
        assertTrue(runCatching { reader(noPdf).novel("Saga") }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun anEditedFileIsParsedAgain() {
        reader.novel("Saga")
        files.files["Saga/Chapter 2.txt"] = "Changed.".toByteArray()
        files.modified["Saga/Chapter 2.txt"] = 2

        val first = requireNotNull(LocalSourceUrls.parse(reader.novel("Saga").chapters[0].url))
        assertEquals("<p>Changed.</p>", reader.chapterHtml(first))
    }

    @Test
    fun namesSortNaturallyWithoutTheirExtensions() {
        val names = listOf("Chapter 12.txt", "Chapter 12.5.txt", "Chapter 13.txt", "Vol 1.epub", "Vol 1.5.epub", "chapter 2.txt")

        assertEquals(
            listOf("chapter 2.txt", "Chapter 12.txt", "Chapter 12.5.txt", "Chapter 13.txt", "Vol 1.epub", "Vol 1.5.epub"),
            names.sortedWith(LocalNames.natural),
        )
    }
}
