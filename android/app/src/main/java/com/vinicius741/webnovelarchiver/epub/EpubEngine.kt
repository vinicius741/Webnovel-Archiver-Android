package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubResult
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.settings.SettingsValidation
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB generation (Maintainability M1: split out of Engines.kt). Builds EPUB 2.0 ZIP/XML into a
 * byte array per volume; covers are fetched through the shared [NetworkClient] (R6).
 */
class EpubEngine(
    private val repository: AppRepository,
    private val network: NetworkClient,
) {
    private val storage = repository.storage
    private val _lastCleanupResult = MutableStateFlow<EpubCleanupResult?>(null)
    val lastCleanupResult: StateFlow<EpubCleanupResult?> = _lastCleanupResult.asStateFlow()

    private data class CoverAsset(
        val data: ByteArray,
        val href: String,
        val mediaType: String,
    )

    suspend fun generate(
        story: Story,
        chapters: List<Chapter>,
        maxPerFile: Int,
        originalChapterNumbers: List<Int>? = null,
        progress: (String) -> Unit = {},
    ): List<EpubResult> =
        withContext(Dispatchers.IO) {
            val available = chapters.filter { it.content != null || storage.readChapter(it) != null }
            if (available.isEmpty()) error("No downloaded chapters available")
            val chaptersPerFile =
                maxPerFile.coerceIn(
                    SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN,
                    SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX,
                )
            val chunks = available.chunked(chaptersPerFile)
            val results = mutableListOf<EpubResult>()
            val chapterNumberById =
                chapters
                    .mapIndexed { index, chapter ->
                        chapter.id to (originalChapterNumbers?.getOrNull(index) ?: (index + 1))
                    }.toMap()
            chunks.forEachIndexed { index, chunk ->
                progress("Generating EPUB ${index + 1}/${chunks.size}...")
                val start = chapterNumberById[chunk.first().id] ?: (chapters.indexOf(chunk.first()) + 1)
                val end = chapterNumberById[chunk.last().id] ?: (chapters.indexOf(chunk.last()) + 1)
                val filename = EpubFilename.forRange(story.title, start, end)
                // S5: fetch the cover (suspend) first, then stream the EPUB straight to its final file
                // via a temp+rename (no full ByteArrayOutputStream held in memory), keeping one
                // chapter's XHTML resident at a time.
                val coverAsset = story.coverUrl?.let { fetchCover(it) }
                val file =
                    storage.saveEpubStreamed(story.id, filename) { out ->
                        writeEpub(ZipOutputStream(out), story, chunk, coverAsset)
                    }
                results.add(EpubResult(file.absolutePath, filename, start, end))
            }
            val committed = repository.markEpubGenerated(story.id, results.map { it.uri })
            check(committed != null) {
                "Story was removed while EPUB generation was running"
            }
            _lastCleanupResult.value = applyRetention(story.id, committed.epubPaths.orEmpty().toSet())
            results
        }

    private fun applyRetention(
        storyId: String,
        referencedPaths: Set<String>,
    ): EpubCleanupResult {
        val entries =
            storage.listEpubs(storyId).map { file ->
                EpubStorageEntry(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath), file.length(), file.lastModified())
            }
        val canonicalReferences = referencedPaths.mapTo(mutableSetOf()) { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        val plan = EpubRetentionPolicy.plan(entries, canonicalReferences)
        val deleted =
            plan.delete
                .filter { storage.deleteEpubFile(storyId, it.path) }
                .mapTo(mutableSetOf()) { it.path }
        return EpubRetentionPolicy.result(plan, deleted)
    }

    /**
     * Writes the full EPUB 2.0 structure into [zip]. Streamed entry-by-entry so only one chapter's
     * XHTML is resident at a time (S5). Non-suspend — the cover is fetched before streaming.
     */
    private fun writeEpub(
        zip: ZipOutputStream,
        story: Story,
        chapters: List<Chapter>,
        coverAsset: CoverAsset?,
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
        entry(zip, "OEBPS/cover.xhtml", EpubContent.cover(story, coverAsset?.href))
        entry(zip, "OEBPS/details.xhtml", EpubContent.details(story))
        entry(zip, "OEBPS/toc.xhtml", EpubContent.tableOfContents(chapters))
        chapters.forEachIndexed { i, chapter ->
            entry(zip, "OEBPS/chapter_${i + 1}.xhtml", EpubContent.chapter(chapter, storage.readChapter(chapter) ?: ""))
        }
        entry(zip, "OEBPS/content.opf", EpubMetadata.opf(story, chapters, coverAsset?.let { EpubCoverMetadata(it.href, it.mediaType) }))
        entry(zip, "OEBPS/toc.ncx", EpubMetadata.ncx(story, chapters))
        zip.close()
    }

    private suspend fun fetchCover(url: String): CoverAsset? =
        runCatching {
            // R6: route cover downloads through the shared OkHttp client with a size cap instead of
            // raw URL.openConnection(), which has weaker timeouts/limits and bypasses the rate limiter.
            val data = network.fetchBytes(url) ?: return@runCatching null
            val mediaType = normalizeCoverMediaType(null, url)
            if (!mediaType.startsWith("image/")) return@runCatching null
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

    private fun normalizeCoverMediaType(
        contentType: String?,
        coverUrl: String,
    ): String =
        contentType
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getCoverMediaType(coverUrl)

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
