// DownloadStatus entries stay lowercase on purpose: Gson serializes the enum by its name() into the
// persisted Story JSON, and renaming them (e.g. to IDLE) would break existing on-disk libraries and
// backups. Mirrors the wire-string compat approach used by DownloadJobStatus.
@file:Suppress("EnumNaming", "ktlint:standard:enum-entry-name-case")

package com.vinicius741.webnovelarchiver.domain.model

enum class DownloadStatus {
    idle,
    downloading,
    completed,
    failed,
    paused,
    partial,
}

enum class PublicationStatus {
    unknown,
    ongoing,
    completed,
    outdated,
    hiatus,
}

/** Whether the canonical story page can currently be used for source-backed actions. */
enum class SourceAvailability {
    available,
    not_found,
    access_restricted,
}

/** Typed reason for the most recent source check failure, kept separately from availability. */
enum class SourceFailureKind {
    not_found,
    access_restricted,
    rate_limited,
    server_error,
    offline,
    timeout,
    parse_error,
    transport,
    http_error,
    unknown,
}

/**
 * Persisted health of a story's canonical source page. [availability] changes only for failures
 * that say something about that page itself; transient failures are retained in [lastFailure]
 * without hiding an otherwise available story.
 */
data class SourceSyncState(
    val availability: SourceAvailability = SourceAvailability.available,
    val lastCheckedAt: Long? = null,
    val unavailableSince: Long? = null,
    val consecutiveNotFoundCount: Int = 0,
    val lastFailure: SourceFailureKind? = null,
    val lastHttpStatus: Int? = null,
)

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
    /** Epoch millis when this chapter finished downloading; null for never-downloaded or legacy rows. */
    var downloadedAt: Long? = null,
    var publishedAt: Long? = null,
    /**
     * Patreon post id when this row is an early-access copy from the creator's Patreon rather than a
     * source chapter. Patreon copies always sit after every source chapter and are stored at sync.
     */
    var patreonPostId: String? = null,
    /** Source chapter id that publishes this Patreon copy; the copy is dropped once that chapter downloads. */
    var patreonReplacedBy: String? = null,
) {
    val isPatreonCopy: Boolean get() = patreonPostId != null
}

data class EpubConfig(
    val maxChaptersPerEpub: Int = 150,
    val rangeStart: Int = 1,
    val rangeEnd: Int = 1,
    // SerializedName keeps the historical "startAfterBookmark" JSON key so existing on-disk configs
    // and backups keep deserializing after the field was renamed to reflect its new "start at the
    // bookmark" (include, not skip) semantics.
    @com.google.gson.annotations.SerializedName("startAfterBookmark")
    val startAtBookmark: Boolean = false,
    // Produce an EPUB containing only chapter text: drop the cover image, cover page, description/tags
    // page, and the human-readable Table of Contents. The EPUB-2 NCX (toc.ncx) is still emitted so
    // <spine toc="ncx"> stays valid. Defaults to false so existing on-disk configs keep all front matter.
    val chaptersOnly: Boolean = false,
) {
    companion object {
        const val MAX_CHAPTERS_PER_EPUB_MIN = 10
        const val MAX_CHAPTERS_PER_EPUB_MAX = 1000
    }
}

data class Story(
    var id: String = "",
    var title: String = "",
    var author: String = "",
    var coverUrl: String? = null,
    var description: String? = null,
    /** Locally generated AI synopsis; the source description stays untouched in [description]. */
    var aiDescription: String? = null,
    /** Which synopsis the Details screen displays: true = [aiDescription], false = [description]. */
    var showAiDescription: Boolean = false,
    /** Locally generated AI cover (storage-relative path like [epubPath]); null = source cover in use. */
    var aiCoverPath: String? = null,
    /**
     * Which cover the app displays: true = [aiCoverPath], false = [coverUrl]. Defaults to true
     * because story JSON written before this field existed showed the AI cover whenever one was
     * recorded — display is still gated on a non-blank [aiCoverPath].
     */
    var showAiCover: Boolean = true,
    var sourceUrl: String = "",
    /** Stable provider identity. Null only for legacy data until startup migration resolves it. */
    var sourceId: String? = null,
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
    var patreonUrl: String? = null,
    var patreonStats: PatreonRawStats? = null,
    /** Patreon collection whose paid posts are synced as early-access chapters; null = not linked. */
    var patreonEarlyAccess: PatreonEarlyAccessLink? = null,
    var publicationStatus: PublicationStatus = PublicationStatus.unknown,
    var lastChapterSyncAt: Long? = null,
    var sourceSyncState: SourceSyncState = SourceSyncState(),
    /** Public, source-authored metadata retained separately from generic tags and local state. */
    var sourceMetadata: SourceMetadata = SourceMetadata(),
    /**
     * Explicit indices (into [chapters]) of the chapters sent to AI generation. Null = default
     * behaviour (first downloaded chapters, per AiDescriptionPlanning.CONTEXT_CHAPTER_COUNT).
     * Stale indices (not-downloaded or removed chapters) are ignored at read time, so sync churn
     * never breaks generation. Not saveable empty: the picker requires one chapter; empty = null.
     */
    var aiContextChapterIndices: MutableList<Int>? = null,
    /** Cover-only chapter selection. Null selects evidence across all downloaded chapters, regardless of reading progress. */
    var aiCoverContextChapterIndices: MutableList<Int>? = null,
)

/**
 * Measured Patreon data only — no derived figures are persisted. Prices are converted to USD at
 * capture so re-estimation never re-applies a newer exchange rate; the formula lives in
 * [com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning] and runs at render.
 * On snapshots [tiers] is delta-encoded: null = carry the previous ladder, empty = no paid tiers.
 */
data class PatreonRawStats(
    val capturedAt: Long = 0,
    val paidMembers: Int? = null,
    val totalMembers: Int? = null,
    /** Real pledge sum when the creator shows earnings; null when hidden or unconvertible. */
    val exactMonthlyUsdCents: Long? = null,
    val tiers: List<PatreonRawTier>? = null,
)

/**
 * Links a story to one collection on its creator's Patreon. The ids are permanent; the remaining
 * fields describe the most recent early-access check and are display-only.
 */
data class PatreonEarlyAccessLink(
    val campaignId: String = "",
    val collectionId: String = "",
    val collectionTitle: String = "",
    val lastCheckedAt: Long? = null,
    /** Early-access posts the current Patreon session cannot read (signed out or tier too low). */
    val lockedCount: Int = 0,
    /** User-facing reason the last check could not complete; null after a clean check. */
    val lastError: String? = null,
    /** Why the last check could not read posts for sign-in reasons; null when sign-in was not the problem. */
    val signInIssue: PatreonSignInIssue? = null,
)

enum class PatreonSignInIssue {
    /** No session was saved, so locked posts stayed locked. */
    SIGNED_OUT,

    /** A saved session was refused (401/403), usually because it expired. */
    REJECTED,
}

data class PatreonRawTier(
    /** Price per month in USD cents, converted at capture time. */
    val usdCents: Long = 0,
    /** Members on this tier when Patreon publishes per-tier counts; null when hidden. */
    val members: Int? = null,
)

/**
 * One point in a novel's metric history, captured at [capturedAt] during a sync. Score, chapter
 * count, status, and source [metrics] are captured on every sync; [patreonRaw] only when Patreon
 * stats were refreshed (null reads as "not measured", not zero). Derived dollar figures are never
 * stored — charts recompute them so formula fixes apply to the whole history.
 */
data class StoryMetricSnapshot(
    val capturedAt: Long = 0L,
    val score: String? = null,
    val totalChapters: Int = 0,
    val publicationStatus: PublicationStatus = PublicationStatus.unknown,
    val patreonRaw: PatreonRawStats? = null,
    val metrics: MutableList<SourceMetric> = mutableListOf(),
)

/** All recorded [StoryMetricSnapshot]s for a single story, persisted to `metrics/<storyId>.json`. */
data class StoryMetricHistory(
    val storyId: String = "",
    val snapshots: MutableList<StoryMetricSnapshot> = mutableListOf(),
)

data class Tab(
    val id: String = "",
    val name: String = "",
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ChapterFilterSettings(
    val filterMode: String = "all",
)

data class DisplayPreferences(
    var activeThemeId: String = "obsidian",
    /**
     * Legacy field kept for backup/restore compatibility. Previously labeled "EPUB Volume Folding"
     * in Settings with Cover/Inner chips, but no EPUB path consumed it. Not exposed in the UI;
     * volume size is controlled via max-chapters-per-EPUB instead.
     */
    var foldLayoutMode: String = "auto",
    /** User override for how the app treats the screen size/fold: "auto" (detect), "cover" (force
     *  phone/1-column), or "inner" (force tablet/multi-column). Fed into screen layout planning. */
    var screenLayoutMode: String = "auto",
    /** Multiplier applied to the reader WebView base font-size (1.0 = 18px). Clamped to 0.8–1.6. */
    var readerFontScale: Float = 1.0f,
    /** When true the reader WebView renders on a dark background (dark-reader toggle). */
    var readerDark: Boolean = false,
    /** Persisted Library tab selection. Encoded form of the runtime id (see library tab selection);
     *  `null`/blank means "never set", which resolves to the All tab. Survives app restarts. */
    var libraryTabId: String? = null,
    /** Persisted Library sort option key (e.g. "lastUpdated", "title"). Survives app restarts;
     *  defaults to "lastUpdated" so persisted JSON written before this field exists keeps the prior
     *  behaviour. See [com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization]
     *  for the allowed keys and legacy normalization. */
    var librarySortOption: String = "lastUpdated",
    /** Persisted Library sort direction: true = ascending, false = descending. Survives app restarts;
     *  defaults to false (descending) to match the pre-persistence default. */
    var librarySortAscending: Boolean = false,
    /** When true the Follow Updates list shows a cover thumbnail next to each novel. Survives app
     *  restarts; defaults to false so persisted JSON written before this field exists keeps the
     *  compact row look. */
    var showCoversOnUpdates: Boolean = false,
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
)

/**
 * OpenRouter-backed AI feature settings: generation and decision-model cover selection share one API key;
 * future generators (tags) add their own model fields here. The API key is deliberately NOT part
 * of full backups — it stays device-local.
 */
data class AiSettings(
    val apiKey: String? = null,
    val descriptionModel: String = DEFAULT_DESCRIPTION_MODEL,
    val imageModel: String = DEFAULT_IMAGE_MODEL,
    /** Cover generation mode: one-shot (prompt + image) or staged with an editable prompt in between. */
    val coverOneStep: Boolean = true,
    /** OpenRouter decision model (Decisions API) that scores chapters for automatic cover selection. */
    val decisionModel: String = DEFAULT_DECISION_MODEL,
    /** Chapters automatic cover selection must validate; scanned in batches of this size. */
    val coverEvidenceChapters: Int = DEFAULT_COVER_EVIDENCE_CHAPTERS,
    /** Reasoning effort by OpenRouter text-model id; "default" leaves the choice to the provider. */
    val reasoningEfforts: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Cheap default so a fresh install works without forcing a model choice first. */
        const val DEFAULT_DESCRIPTION_MODEL = "deepseek/deepseek-v4-flash-0731"

        /** Default image generator for AI covers. */
        const val DEFAULT_IMAGE_MODEL = "x-ai/grok-imagine-image-2.0"

        /** Versioned TypeSafe Jev snapshot; a pinned id keeps cached chapter scores valid. */
        const val DEFAULT_DECISION_MODEL = "typesafe/jev-1.13"

        /** Chapters validated per automatic cover scan (also the batch size). */
        const val DEFAULT_COVER_EVIDENCE_CHAPTERS = 10
        const val MIN_COVER_EVIDENCE_CHAPTERS = 3
        const val MAX_COVER_EVIDENCE_CHAPTERS = 25
    }
}

data class DownloadJob(
    var id: String = "",
    var storyId: String = "",
    var storyTitle: String = "",
    /** Stable provider identity copied from the story when the job is queued. */
    var sourceId: String? = null,
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
    val patreonUrl: String? = null,
    val publicationStatus: PublicationStatus = PublicationStatus.unknown,
    /** Public, source-authored metadata retained separately from generic tags and local state. */
    val sourceMetadata: SourceMetadata = SourceMetadata(),
)

/**
 * Native metric families published by story sources. The enum intentionally keeps related values
 * distinct: readers, followers, watchers, and follows are not interchangeable, nor are reviews,
 * likes, and ratings.
 */
enum class SourceMetricKind {
    TOTAL_VIEWS("Total views"),
    TOTAL_VIEWS_CHAPTERS("Chapter views"),
    AVERAGE_VIEWS("Average views"),
    FOLLOWERS("Followers"),
    READERS("Readers"),
    WATCHERS("Watchers"),
    FOLLOWS("Follows"),
    FAVORITES("Favorites"),
    RATINGS("Ratings"),
    REVIEWS("Reviews"),
    REPLIES("Replies"),
    LIKES("Likes"),
    KUDOS("Kudos"),
    BOOKMARKS("Bookmarks"),
    COMMENTS("Comments"),
    WORDS("Words"),
    AVERAGE_WORDS("Average words"),
    PAGES("Pages"),
    CHAPTERS_PER_WEEK("Chapters per week"),
    ;

    val label: String

    constructor(label: String) {
        this.label = label
    }
}

/** One source-reported metric. A missing metric is different from a reported value of zero. */
data class SourceMetric(
    val kind: SourceMetricKind = SourceMetricKind.WORDS,
    val value: Long = 0L,
    val isEstimated: Boolean = false,
)

/**
 * Source-authored facts that do not belong in the generic Story fields. Defaults are deliberately
 * empty so libraries written before this model was added continue to restore cleanly.
 */
data class SourceMetadata(
    var metrics: MutableList<SourceMetric> = mutableListOf(),
    var createdAt: Long? = null,
    var publishedAt: Long? = null,
    var updatedAt: Long? = null,
    var contentRating: String? = null,
    var contentWarnings: MutableList<String> = mutableListOf(),
    var sourceType: String? = null,
    var sourceCategory: String? = null,
    var sourceListingState: String? = null,
    var sourceStatus: String? = null,
    var language: String? = null,
    var genres: MutableList<String> = mutableListOf(),
    var fandoms: MutableList<String> = mutableListOf(),
    var characters: MutableList<String> = mutableListOf(),
    var ratingDistribution: MutableMap<Int, Int> = mutableMapOf(),
)

data class ChapterInfo(
    val id: String? = null,
    val title: String = "",
    val url: String = "",
    val chapterNumber: Int? = null,
    val publishedAt: Long? = null,
)

data class EpubResult(
    val uri: String,
    val filename: String,
    val startChapter: Int,
    val endChapter: Int,
)
