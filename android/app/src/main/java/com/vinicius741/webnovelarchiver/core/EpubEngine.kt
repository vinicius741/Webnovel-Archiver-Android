package com.vinicius741.webnovelarchiver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB generation (Maintainability M1: split out of Engines.kt). Builds EPUB 2.0 ZIP/XML into a
 * byte array per volume; covers are fetched through the shared [NetworkClient] (R6).
 */
class EpubEngine(private val storage: AppStorage, private val network: NetworkClient) {
    private data class CoverAsset(val data: ByteArray, val href: String, val mediaType: String)

    suspend fun generate(
        story: Story,
        chapters: List<Chapter>,
        maxPerFile: Int,
        originalChapterNumbers: List<Int>? = null,
        progress: (String) -> Unit = {},
    ): List<EpubResult> = withContext(Dispatchers.IO) {
        val available = chapters.filter { it.content != null || it.filePath?.let { path -> File(path).exists() } == true }
        if (available.isEmpty()) error("No downloaded chapters available")
        val chaptersPerFile = maxPerFile.coerceIn(
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN,
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX,
        )
        val chunks = available.chunked(chaptersPerFile)
        val results = mutableListOf<EpubResult>()
        val chapterNumberById = chapters.mapIndexed { index, chapter ->
            chapter.id to (originalChapterNumbers?.getOrNull(index) ?: (index + 1))
        }.toMap()
        chunks.forEachIndexed { index, chunk ->
            progress("Generating EPUB ${index + 1}/${chunks.size}...")
            val start = chapterNumberById[chunk.first().id] ?: (chapters.indexOf(chunk.first()) + 1)
            val end = chapterNumberById[chunk.last().id] ?: (chapters.indexOf(chunk.last()) + 1)
            val filename = EpubFilename.forRange(story.title, start, end)
            val bytes = buildEpub(story, chunk)
            val file = storage.saveEpub(story.id, filename, bytes)
            results.add(EpubResult(file.absolutePath, filename, start, end))
        }
        story.epubPaths = results.map { it.uri }.toMutableList()
        story.epubPath = results.firstOrNull()?.uri
        story.epubStale = false
        storage.addOrUpdateStory(story)
        results
    }

    private suspend fun buildEpub(story: Story, chapters: List<Chapter>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ArchiveUtils.putStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray())
            entry(zip, "META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry(zip, "OEBPS/style.css", EpubContent.css())
            val coverAsset = story.coverUrl?.let { fetchCover(it) }
            coverAsset?.let { cover ->
                zip.putNextEntry(ZipEntry("OEBPS/${cover.href}"))
                zip.write(cover.data)
                zip.closeEntry()
            }
            entry(zip, "OEBPS/cover.xhtml", EpubContent.cover(story, coverAsset?.href))
            entry(zip, "OEBPS/details.xhtml", EpubContent.details(story))
            entry(zip, "OEBPS/toc.xhtml", EpubContent.tableOfContents(chapters))
            chapters.forEachIndexed { i, chapter ->
                entry(zip, "OEBPS/chapter_${i + 1}.xhtml", EpubContent.chapter(chapter, storage.readChapter(chapter) ?: ""))
            }
            entry(zip, "OEBPS/content.opf", EpubMetadata.opf(story, chapters, coverAsset?.let { EpubCoverMetadata(it.href, it.mediaType) }))
            entry(zip, "OEBPS/toc.ncx", EpubMetadata.ncx(story, chapters))
        }
        return out.toByteArray()
    }

    private suspend fun fetchCover(url: String): CoverAsset? = runCatching {
        // R6: route cover downloads through the shared OkHttp client with a size cap instead of
        // raw URL.openConnection(), which has weaker timeouts/limits and bypasses the rate limiter.
        val data = network.fetchBytes(url) ?: return@runCatching null
        val mediaType = normalizeCoverMediaType(null, url)
        if (!mediaType.startsWith("image/")) return@runCatching null
        val extension = getCoverExtension(url, mediaType)
        CoverAsset(data, "images/cover.$extension", mediaType)
    }.getOrNull()

    private fun entry(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    private fun normalizeCoverMediaType(contentType: String?, coverUrl: String): String = contentType
        ?.substringBefore(";")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: getCoverMediaType(coverUrl)

    private fun getCoverMediaType(coverUrl: String): String {
        val extension = getExtensionFromUrl(coverUrl)
        return coverMediaTypes[extension] ?: "image/jpeg"
    }

    private fun getCoverExtension(coverUrl: String, mediaType: String): String {
        val extension = getExtensionFromUrl(coverUrl)
        if (extension != null && coverMediaTypes[extension] == mediaType) return extension
        val matched = coverMediaTypes.entries.firstOrNull { it.value == mediaType }?.key
        return if (matched == "jpeg") "jpg" else matched ?: "jpg"
    }

    private fun getExtensionFromUrl(url: String): String? = runCatching {
        URL(url).path.substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotBlank() }
    }.getOrElse {
        url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotBlank() }
    }

    private val coverMediaTypes = mapOf(
        "gif" to "image/gif",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
    )
}
