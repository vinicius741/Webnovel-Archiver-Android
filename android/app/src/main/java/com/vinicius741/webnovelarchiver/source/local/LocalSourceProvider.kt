package com.vinicius741.webnovelarchiver.source.local

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.source.LoadedSourceStory
import com.vinicius741.webnovelarchiver.source.SourceCapabilities
import com.vinicius741.webnovelarchiver.source.SourceDescriptor
import com.vinicius741.webnovelarchiver.source.SourceKind
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Novels from the user's local folder (EPUB, PDF, TXT, Markdown, HTML), read through
 * [LocalNovelReader]. Sync rescans the novel's files; "downloading" a chapter reads it from its
 * file, without pacing, so after an import every chapter is in the library like a web chapter.
 * The app installs the reader at startup ([install]).
 */
object LocalSourceProvider : SourceProvider {
    override val descriptor =
        SourceDescriptor(
            id = "local",
            displayName = "Local",
            browseUrl = "",
            hosts = setOf(LocalSourceUrls.HOST),
            capabilities = SourceCapabilities(bulkDownloadPreflight = false),
            kind = SourceKind.LOCAL,
        )

    @Volatile private var installed: LocalNovelReader? = null

    fun install(reader: LocalNovelReader) {
        installed = reader
    }

    val reader: LocalNovelReader get() = installed ?: throw LocalFolderUnavailableException()

    override fun classifyUrl(url: String): SourceUrlKind? {
        val address = LocalSourceUrls.parse(url) ?: return null
        return if (address.file == null && address.part == null) SourceUrlKind.STORY else SourceUrlKind.CHAPTER
    }

    override fun normalizeStoryUrl(url: String): String = LocalSourceUrls.parse(url)?.let { LocalSourceUrls.story(it.entry) } ?: url.trim()

    override fun getStoryId(url: String): String =
        LocalSourceUrls.storyId(requireNotNull(LocalSourceUrls.parse(url)) { "Not a local novel URL: $url" }.entry)

    override fun getChapterId(url: String): String? = LocalSourceUrls.parse(url)?.let(LocalSourceUrls::chapterId)

    override suspend fun loadStory(
        url: String,
        preferLatestChapters: Boolean,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): LoadedSourceStory {
        val address = requireNotNull(LocalSourceUrls.parse(url)) { "Not a local novel URL: $url" }
        progress("Reading \"${address.entry}\"...")
        val novel =
            withContext(Dispatchers.IO) {
                // Recognizing a scanned PDF takes minutes; a cancelled import stops at the next page.
                reader.novel(address.entry) { status ->
                    coroutineContext.ensureActive()
                    progress(status)
                }
            }
        return LoadedSourceStory(novel.metadata, novel.chapters, chaptersAreLatestOnly = false) { novel.chapters }
    }

    @Suppress("TooGenericExceptionCaught") // Every file failure becomes a typed, never-retried LocalSourceException.
    override suspend fun fetchChapterContent(
        storyUrl: String,
        chapter: Chapter,
        chapterIndex: Int,
        network: NetworkClient,
        requestGate: NetworkRequestGate?,
    ): String {
        val address = LocalSourceUrls.parse(chapter.url) ?: throw LocalSourceException("Not a local chapter: ${chapter.url}")
        return withContext(Dispatchers.IO) {
            try {
                reader.chapterHtml(address)
            } catch (error: LocalSourceException) {
                throw error
            } catch (error: java.io.IOException) {
                throw LocalSourceException("Could not read \"${address.file ?: address.entry}\": ${error.message}", error)
            } catch (error: RuntimeException) {
                // A parse failure is a fact about the file; typed so the queue never retries it.
                throw LocalSourceException("Could not read \"${address.file ?: address.entry}\": ${error.message}", error)
            }
        }
    }

    // The HTML pipeline is unused: [loadStory] and [fetchChapterContent] read files directly.
    override fun parseMetadata(html: String): NovelMetadata = error("Local novels are read from files")

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> = error("Local novels are read from files")

    override fun parseChapterContent(html: String): String = error("Local novels are read from files")
}
