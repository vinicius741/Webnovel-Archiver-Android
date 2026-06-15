package com.vinicius741.webnovelarchiver.core

enum class DownloadStatus {
    idle, downloading, completed, failed, paused, partial
}

data class Chapter(
    var id: String = "",
    var title: String = "",
    var url: String = "",
    var content: String? = null,
    var filePath: String? = null,
    var downloaded: Boolean = false,
)

data class EpubConfig(
    var maxChaptersPerEpub: Int = 150,
    var rangeStart: Int = 1,
    var rangeEnd: Int = 1,
    var startAfterBookmark: Boolean = false,
)

data class Story(
    var id: String = "",
    var title: String = "",
    var author: String = "",
    var coverUrl: String? = null,
    var description: String? = null,
    var sourceUrl: String = "",
    var status: DownloadStatus = DownloadStatus.idle,
    var totalChapters: Int = 0,
    var downloadedChapters: Int = 0,
    var chapters: MutableList<Chapter> = mutableListOf(),
    var lastUpdated: Long? = null,
    var dateAdded: Long? = null,
    var epubPath: String? = null,
    var epubPaths: MutableList<String>? = null,
    var epubStale: Boolean? = null,
    var epubConfig: EpubConfig? = null,
    var pendingNewChapterIds: MutableList<String>? = null,
    var tags: MutableList<String>? = null,
    var lastReadChapterId: String? = null,
    var score: String? = null,
    var tabId: String? = null,
    var isArchived: Boolean? = null,
    var archiveOfStoryId: String? = null,
    var archivedAt: Long? = null,
    var archiveReason: String? = null,
)

data class Tab(
    var id: String = "",
    var name: String = "",
    var order: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
)

data class AppSettings(
    var downloadConcurrency: Int = 1,
    var downloadDelay: Long = 500,
    var maxChaptersPerEpub: Int = 150,
)

data class SourceDownloadSettings(
    var concurrency: Int = 1,
    var delay: Long = 500,
)

data class ChapterFilterSettings(
    var filterMode: String = "all",
)

data class DisplayPreferences(
    var activeThemeId: String = "obsidian",
    var foldLayoutMode: String = "auto",
    /** Multiplier applied to the reader WebView base font-size (1.0 = 18px). Clamped to 0.8–1.6. */
    var readerFontScale: Float = 1.0f,
    /** When true the reader WebView renders on a dark background (dark-reader toggle). */
    var readerDark: Boolean = false,
)

data class RegexCleanupRule(
    var id: String = "",
    var name: String = "",
    var pattern: String = "",
    var flags: String = "",
    var enabled: Boolean = true,
    var appliesTo: String = "both",
)

data class TtsSettings(
    var pitch: Float = 1.0f,
    var rate: Float = 1.0f,
    var voiceIdentifier: String? = null,
    var chunkSize: Int = 500,
)

data class TtsSession(
    var storyId: String = "",
    var chapterId: String = "",
    var chapterTitle: String = "",
    var currentChunkIndex: Int = 0,
    var isPaused: Boolean = false,
    var wasPlaying: Boolean = false,
    var chunkSize: Int = 500,
    var voiceIdentifier: String? = null,
    var rate: Float = 1.0f,
    var pitch: Float = 1.0f,
    var updatedAt: Long = System.currentTimeMillis(),
    var sessionVersion: Int = 1,
)

data class DownloadJob(
    var id: String = "",
    var storyId: String = "",
    var storyTitle: String = "",
    var chapterIndex: Int = 0,
    var chapter: Chapter = Chapter(),
    var status: String = "pending",
    var addedAt: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    var maxRetries: Int = 3,
    var error: String? = null,
    var errorCategory: String? = null,
    var errorCode: String? = null,
    var nextRetryAt: Long? = null,
)

data class NovelMetadata(
    var title: String = "Unknown Title",
    var author: String = "Unknown Author",
    var coverUrl: String? = null,
    var description: String? = null,
    var tags: MutableList<String>? = null,
    var score: String? = null,
    var canonicalUrl: String? = null,
)

data class ChapterInfo(
    var id: String? = null,
    var title: String = "",
    var url: String = "",
    var chapterNumber: Int? = null,
)

data class EpubResult(
    val uri: String,
    val filename: String,
    val startChapter: Int,
    val endChapter: Int,
)
