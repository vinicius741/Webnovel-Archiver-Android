package com.vinicius741.webnovelarchiver.core

enum class DownloadStatus {
    idle,
    downloading,
    completed,
    failed,
    paused,
    partial,
}

/**
 * Typed lifecycle state for a [DownloadJob] (Reliability R4). The [wire] string preserves the
 * lowercase on-disk/JSON form used historically, so existing `download_queue.json` files and JSON
 * backups keep deserializing. [parse] tolerates any legacy value by mapping unknowns to [Failed].
 */
enum class DownloadJobStatus(
    val wire: String,
) {
    Pending("pending"),
    Downloading("downloading"),
    Paused("paused"),
    Completed("completed"),
    Failed("failed"),
    Cancelled("cancelled"),
    ;

    companion object {
        fun parse(value: String?): DownloadJobStatus = values().firstOrNull { it.wire == value } ?: Failed

        /** All wire strings — used by Gson to serialize/parse the legacy string field. */
        val wires: Set<String> = values().map { it.wire }.toSet()

        /** Wire strings for jobs that count as "in progress" (queued or actively downloading). */
        val activeWires: Set<String> = setOf(Pending.wire, Downloading.wire)

        /** Wire strings for jobs the user can still cancel (not yet terminal). */
        val cancellableWires: Set<String> = setOf(Pending.wire, Downloading.wire, Paused.wire)

        /** Wire strings for terminal jobs (no further lifecycle moves). */
        val terminalWires: Set<String> = setOf(Completed.wire, Failed.wire, Cancelled.wire)
    }
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
    val maxChaptersPerEpub: Int = 150,
    val rangeStart: Int = 1,
    val rangeEnd: Int = 1,
    val startAfterBookmark: Boolean = false,
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
    val id: String = "",
    val name: String = "",
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class AppSettings(
    val downloadConcurrency: Int = 1,
    val downloadDelay: Long = 500,
    val maxChaptersPerEpub: Int = 150,
)

data class SourceDownloadSettings(
    val concurrency: Int = 1,
    val delay: Long = 500,
)

data class ChapterFilterSettings(
    val filterMode: String = "all",
)

data class DisplayPreferences(
    var activeThemeId: String = "obsidian",
    var foldLayoutMode: String = "auto",
    /** User override for how the app treats the screen size/fold: "auto" (detect), "cover" (force
     *  phone/1-column), or "inner" (force tablet/multi-column). Fed into [resolveScreenLayout].
     *  NOTE: unrelated to [foldLayoutMode], which only governs EPUB volume nesting. */
    var screenLayoutMode: String = "auto",
    /** Multiplier applied to the reader WebView base font-size (1.0 = 18px). Clamped to 0.8–1.6. */
    var readerFontScale: Float = 1.0f,
    /** When true the reader WebView renders on a dark background (dark-reader toggle). */
    var readerDark: Boolean = false,
    /** Persisted Library tab selection. Encoded form of the runtime id (see [LibraryTabSelection]);
     *  `null`/blank means "never set", which resolves to the All tab. Survives app restarts. */
    var libraryTabId: String? = null,
)

data class RegexCleanupRule(
    val id: String = "",
    val name: String = "",
    val pattern: String = "",
    val flags: String = "",
    val enabled: Boolean = true,
    val appliesTo: String = "both",
)

data class TtsSettings(
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    val voiceIdentifier: String? = null,
    val chunkSize: Int = 500,
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
    val title: String = "Unknown Title",
    val author: String = "Unknown Author",
    val coverUrl: String? = null,
    val description: String? = null,
    val tags: MutableList<String>? = null,
    val score: String? = null,
    val canonicalUrl: String? = null,
)

data class ChapterInfo(
    val id: String? = null,
    val title: String = "",
    val url: String = "",
    val chapterNumber: Int? = null,
)

data class EpubResult(
    val uri: String,
    val filename: String,
    val startChapter: Int,
    val endChapter: Int,
)
