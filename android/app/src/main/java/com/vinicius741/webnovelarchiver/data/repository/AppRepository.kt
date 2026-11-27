package com.vinicius741.webnovelarchiver.data.repository

import android.net.Uri
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogLibraryStats
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.data.storage.AiCoverResultKind
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.data.storage.LocalImageRevision
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import com.vinicius741.webnovelarchiver.domain.sha256Hex
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.domain.story.RemovedChapterPlanning
import com.vinicius741.webnovelarchiver.sync.storyChapterKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

private class AppStorageStoryStore(
    private val storage: AppStorage,
) : RepositoryStoryStore {
    override val transactionLock: Any get() = storage

    override fun stories(): List<Story> = storage.getLibrary()

    override fun addOrUpdateStory(story: Story) = storage.addOrUpdateStory(story)

    override fun saveLibraryMember(story: Story) = storage.saveLibraryMember(story)

    override fun deleteStory(id: String) = storage.deleteStory(id)

    override fun saveLibrary(stories: List<Story>) = storage.saveLibrary(stories)

    override fun queue(): List<DownloadJob> = storage.getQueue()

    override fun saveQueue(jobs: List<DownloadJob>) = storage.saveQueue(jobs)

    override fun displayPreferences(): DisplayPreferences = storage.displayPreferencesDoc.get()

    override fun saveDisplayPreferences(preferences: DisplayPreferences) = storage.displayPreferencesDoc.set(preferences)
}

data class DownloadUiSnapshot(
    val version: Long = 0L,
    val libraryVersion: Long = 0L,
    val queueVersion: Long = 0L,
    val library: List<Story> = emptyList(),
    val queue: List<DownloadJob> = emptyList(),
)

/**
 * Single owner of [AppStorage]. Library, queue, and settings transactions synchronize on [storage],
 * the same monitor [AppStorage] and the download engine use, so the activity and the foreground
 * service cannot race on `download_queue.json` or per-story files.
 *
 * [downloadState] is refreshed after each transaction so screens never re-read JSON per render.
 * Engines still use [storage] directly for raw file I/O; only read-modify-write mutations live here.
 *
 * The working library and queue are the read source of every transaction: after [refresh] loads
 * them, story and queue documents are only written (write-through), never re-parsed. That holds only
 * while every story/queue writer goes through this class, including the download loop's queue
 * saves (`download/DownloadQueueStore.kt`); restore, import and clear replace the root and [refresh].
 */
class AppRepository private constructor(
    private val storyStore: RepositoryStoryStore,
    private val storageDelegate: AppStorage?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        storage: AppStorage,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(AppStorageStoryStore(storage), storage, ioDispatcher)

    internal constructor(
        storyStore: RepositoryStoryStore,
        ioDispatcher: CoroutineDispatcher,
    ) : this(storyStore, null, ioDispatcher)

    /** The shared storage monitor; the `*Locked` members below require the caller to hold it. */
    internal val transactionLock: Any get() = storyStore.transactionLock

    /** Infrastructure escape hatch; feature/UI code only sees repository operations. */
    val storage: AppStorage
        get() = checkNotNull(storageDelegate) { "This repository operation requires AppStorage" }

    private val requiredStorage: AppStorage get() = storage

    /** Runs a persistence mutation on the I/O dispatcher under the repository's shared lock. */
    internal suspend fun <T> storageTransaction(block: () -> T): T = withContext(ioDispatcher) { synchronized(transactionLock, block) }

    /**
     * Monotonic identity of the persisted library. Clear and restore replace the whole library, so
     * they bump the generation; long-running network work captures it at start and rechecks at
     * commit, rejecting results that belong to an obsolete library.
     */
    private val libraryGeneration =
        java.util.concurrent.atomic
            .AtomicLong(0)

    fun libraryGeneration(): Long = libraryGeneration.get()

    /**
     * Immutable published view of library + queue. UI reads ([library], [story], [queue]) take this
     * reference without the storage monitor, so a long backup/restore/EPUB transaction holding the
     * monitor can never block a render or a click handler.
     */
    private class PublishedState(
        val libraryById: Map<String, Story> = emptyMap(),
        val library: List<Story> = emptyList(),
        val queue: List<DownloadJob> = emptyList(),
    )

    @Volatile
    private var published = PublishedState()

    /**
     * Working library/queue state, mirroring what is on disk; only touched while holding the
     * transaction monitor. Entries are never handed out: callers get copies.
     */
    private val libraryById = linkedMapOf<String, Story>()
    private var queueJobs: List<DownloadJob> = emptyList()

    /** Write-through changes made by engines under the monitor that the next publish must announce. */
    private var unpublishedLibrary = false
    private var unpublishedQueue = false

    /**
     * False until [refresh] (or the first transaction) loads the working state. The download service
     * is sticky and may run a transaction before startup's refresh; it must never act on, or save,
     * an empty working queue.
     */
    private var workingStateLoaded = false

    private val _downloadState = MutableStateFlow(DownloadUiSnapshot())

    /** Typed process-wide snapshot of coherent library and queue state that UI surfaces bind to. */
    val downloadState: StateFlow<DownloadUiSnapshot> = _downloadState.asStateFlow()

    private fun publishedStateLocked(
        libraryById: LinkedHashMap<String, Story>,
        queue: List<DownloadJob>,
    ): PublishedState =
        PublishedState(
            // Copy: wrapping the working map would publish a live view racing in-place mutation.
            libraryById = java.util.Collections.unmodifiableMap(HashMap(libraryById)),
            library = libraryById.values.toList(),
            queue = queue,
        )

    internal val aiUsage = AiUsageStore()

    internal val aiCoverUnseenState = MutableStateFlow<Map<String, AiCoverResultKind>>(emptyMap())

    /** Stories with a finished background cover result the user has not looked at yet. Device-local, never backed up. */
    val aiCoverUnseen: StateFlow<Map<String, AiCoverResultKind>> = aiCoverUnseenState.asStateFlow()

    @Volatile
    internal var ttsSession: TtsSession? = null

    /**
     * Loads the current library + queue + settings into the state flows. Call once at startup.
     * [preloadedLibrary] lets startup reuse the one library pass it already ran instead of
     * re-reading every story document.
     */
    fun refresh(preloadedLibrary: List<Story>? = null) {
        synchronized(transactionLock) {
            loadWorkingStateLocked(preloadedLibrary)
            storageDelegate?.let { storage ->
                // Settings documents are cached by the storage layer; reload them so a restore/import
                // that replaced the root's contents is reflected, then re-read the session cache.
                storage.reloadJsonDocuments()
                aiUsage.reload(storage.aiUsage::load)
                ttsSession = storage.getTtsSession()?.copy()
                aiCoverUnseenState.value = storage.covers.unseenResults()
            }
        }
    }

    /** Loads the working library and queue from the store; the only place either is parsed. */
    private fun loadWorkingStateLocked(preloadedLibrary: List<Story>?) {
        val library = (preloadedLibrary ?: storyStore.stories()).map(StoryMutations::snapshot)
        val queue = storyStore.queue().map(::snapshotJob)
        libraryById.clear()
        library.forEach { libraryById[it.id] = it }
        queueJobs = queue
        workingStateLoaded = true
        unpublishedLibrary = false
        unpublishedQueue = false
        republishLocked()
        _downloadState.value = _downloadState.value.copy(library = library, queue = queue)
    }

    /**
     * Publishes the write-through changes engines made under the monitor (download loop claims,
     * failure handling, enqueue). Every screen stays in sync because the service and the Activity
     * share this repository. Nothing is re-read: the working state already holds the change.
     */
    internal fun publishDownloadState() {
        synchronized(transactionLock) {
            if (unpublishedLibrary || unpublishedQueue) publishDownloadStateLocked(libraryChanged = false, queueChanged = false)
        }
    }

    private fun publishDownloadStateLocked(
        libraryChanged: Boolean,
        queueChanged: Boolean,
    ) {
        val previous = _downloadState.value
        val library = libraryById.values.toList()
        val queue = queueJobs
        published = publishedStateLocked(libraryById, queue)
        val version = previous.version + 1L
        _downloadState.value =
            DownloadUiSnapshot(
                version = version,
                libraryVersion = if (libraryChanged || unpublishedLibrary) previous.libraryVersion + 1L else previous.libraryVersion,
                queueVersion = if (queueChanged || unpublishedQueue) previous.queueVersion + 1L else previous.queueVersion,
                library = library,
                queue = queue,
            )
        unpublishedLibrary = false
        unpublishedQueue = false
    }

    /** Refreshes the published snapshots after in-place working-state changes (refresh/startup). */
    private fun republishLocked() {
        published = publishedStateLocked(libraryById, queueJobs)
    }

    /** Every working-state access goes through here: the monitor must be held, and the state loaded. */
    private fun requireLocked() {
        check(Thread.holdsLock(transactionLock)) { "Working state is read only under the storage monitor" }
        if (!workingStateLoaded) loadWorkingStateLocked(null)
    }

    /** A copy of one working-state story for the caller to change; null when the library lacks it. */
    internal fun storyForWriteLocked(id: String): Story? {
        requireLocked()
        return libraryById[id]?.let(StoryMutations::snapshot)
    }

    internal fun containsStoryLocked(id: String): Boolean {
        requireLocked()
        return id in libraryById
    }

    /**
     * Write-through: persists [story] and makes it the working copy. A library member is rewritten
     * without any read, keeping its recorded `dateAdded`; a new id takes the store's membership
     * path, which also writes the library index. A failed write leaves the working copy untouched.
     */
    internal fun saveStoryLocked(story: Story) {
        requireLocked()
        val existing = libraryById[story.id]
        if (existing != null) {
            story.dateAdded = existing.dateAdded ?: story.dateAdded
            storyStore.saveLibraryMember(story)
        } else {
            storyStore.addOrUpdateStory(story)
        }
        libraryById[story.id] = StoryMutations.snapshot(story)
        unpublishedLibrary = true
    }

    /** A mutable copy of the working queue; the caller may change the jobs in place before [saveQueueLocked]. */
    internal fun queueForWriteLocked(): MutableList<DownloadJob> {
        requireLocked()
        return queueJobs.mapTo(ArrayList(queueJobs.size), ::snapshotJob)
    }

    /** Write-through queue save; the working copy changes only after the write succeeded. */
    internal fun saveQueueLocked(jobs: List<DownloadJob>) {
        requireLocked()
        storyStore.saveQueue(jobs)
        queueJobs = jobs.map(::snapshotJob)
        unpublishedQueue = true
    }

    /** Persists an enqueued story with its new queue in one monitor hold (see `DownloadEnqueueTransaction`). */
    internal fun saveEnqueueLocked(
        story: Story,
        jobs: List<DownloadJob>,
    ) {
        saveStoryLocked(story)
        saveQueueLocked(jobs)
    }

    /** The story's source URL from the published state, without copying its chapter list. */
    internal fun storySourceUrl(id: String): String? = published.libraryById[id]?.sourceUrl

    /** Cached library snapshot, served from the lock-free published state. */
    fun library(): List<Story> = published.library.map(StoryMutations::snapshot)

    /** Cached queue snapshot. */
    fun queue(): List<DownloadJob> = published.queue.map(::snapshotJob)

    /** Single-story lookup by id, from the lock-free published state (never waits on storage I/O). */
    fun story(id: String): Story? = published.libraryById[id]?.let(StoryMutations::snapshot)

    /** Adds or replaces one story without rebuilding/re-parsing the rest of the library. */
    suspend fun addOrUpdateStory(story: Story) = upsertStory(story)

    /** One cached settings document as callers see it: defensive-copy reads and transactional saves. */
    inner class Setting<T : Any> internal constructor(
        private val document: () -> AppStorage.JsonDocument<T>,
        private val snapshot: (T) -> T = { it },
    ) {
        fun get(): T = snapshot(document().get())

        suspend fun save(value: T) {
            storageTransaction { document().set(value) }
        }
    }

    val settings = Setting({ requiredStorage.settingsDoc }) { it.copy() }
    val sourceDownloadSettings = Setting({ requiredStorage.sourceDownloadSettingsDoc }) { it.toMap() }
    val chapterFilterSettings = Setting({ requiredStorage.chapterFilterSettingsDoc }) { it.copy() }
    val tabs = Setting({ requiredStorage.tabsDoc }) { tabs -> tabs.map { it.copy() } }
    val sentenceRemovalList = Setting({ requiredStorage.sentencesDoc }) { it.toList() }
    val regexRules = Setting({ requiredStorage.regexRulesDoc }) { rules -> rules.map { it.copy() } }
    val ttsSettings = Setting({ requiredStorage.ttsSettingsDoc }) { it.copy() }
    val aiSettings = Setting({ requiredStorage.aiSettingsDoc }) { it.copy() }

    fun getDisplayPreferences(): DisplayPreferences = storyStore.displayPreferences().copy()

    fun getStorageHealth(): StorageHealthSnapshot = requiredStorage.storageHealth.value

    suspend fun readChapter(chapter: Chapter): String? = withContext(ioDispatcher) { requiredStorage.readChapter(chapter) }

    /** A cheap stamp of the chapter's stored text (no read); see [AppStorage.chapterTextStamp]. Call off the main thread. */
    fun chapterTextStamp(chapter: Chapter): String? = requiredStorage.chapterTextStamp(chapter)

    suspend fun overwriteChapter(
        chapter: Chapter,
        html: String,
    ): Boolean = withContext(ioDispatcher) { requiredStorage.overwriteChapter(chapter, html) }

    fun resolveAbsolutePath(path: String?): File? = requiredStorage.resolveAbsolutePath(path)

    suspend fun listEpubs(storyId: String): List<File> = withContext(ioDispatcher) { requiredStorage.listEpubs(storyId) }

    suspend fun getMetricHistory(storyId: String): StoryMetricHistory =
        withContext(ioDispatcher) { requiredStorage.getMetricHistory(storyId) }

    suspend fun appendMetricSnapshot(
        storyId: String,
        snapshot: StoryMetricSnapshot,
    ): Unit = withContext(ioDispatcher) { requiredStorage.appendMetricSnapshot(storyId, snapshot) }

    suspend fun deleteMetricHistory(storyId: String): Unit = withContext(ioDispatcher) { requiredStorage.deleteMetricHistory(storyId) }

    suspend fun deleteEpubFile(
        storyId: String,
        path: String,
    ): Boolean = withContext(ioDispatcher) { requiredStorage.deleteEpubFile(storyId, path) }

    suspend fun exportFullBackup(onProgress: (String) -> Unit = {}): File =
        loggedBackup("full_export") { withContext(ioDispatcher) { requiredStorage.exportFullBackup(onProgress) } }

    suspend fun importBackupUri(uri: Uri): String = loggedBackup("json_import") { replaceLibrary { requiredStorage.importBackupUri(uri) } }

    suspend fun importFullBackupUri(uri: Uri): String =
        loggedBackup("full_restore") { replaceLibrary { requiredStorage.importFullBackupUri(uri) } }

    /**
     * Records the start and outcome of a backup action with the library's size before and after,
     * so an activity log shows exactly what an import or restore changed.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> loggedBackup(
        action: String,
        block: suspend () -> T,
    ): T {
        val before = ActivityLogLibraryStats.of(published.library).fields("before")
        ActivityLog.recordAll(ActivityCategory.BACKUP, "${action}_started", before)
        return try {
            block().also { result ->
                ActivityLog.recordAll(
                    ActivityCategory.BACKUP,
                    "${action}_finished",
                    listOf("result" to if (result is File) result.name else result) +
                        before +
                        ActivityLogLibraryStats.of(published.library).fields("after"),
                )
            }
        } catch (error: Throwable) {
            ActivityLog.record(ActivityCategory.BACKUP, "${action}_failed", "error" to error)
            throw error
        }
    }

    suspend fun clearAll() = replaceLibrary { requiredStorage.clearAll() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> replaceLibrary(block: () -> T): T =
        storageTransaction {
            invalidateLibraryGeneration()
            try {
                block().also { refresh() }
            } catch (error: Throwable) {
                // Transactions read the working state, so it must match whatever the root holds
                // after a failure too (a rolled-back restore, a partly cleared root).
                runCatching { refresh() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            } finally {
                // Work started against the old published snapshot during maintenance is stale too.
                invalidateLibraryGeneration()
            }
        }

    /** Invalidates in-flight library-scoped work; called by clear/restore/import. */
    internal fun invalidateLibraryGeneration() {
        libraryGeneration.incrementAndGet()
    }

    suspend fun saveDisplayPreferences(preferences: DisplayPreferences) =
        storageTransaction { requiredStorage.displayPreferencesDoc.set(preferences) }

    /**
     * Read-modify-write display preferences against the LATEST persisted value inside one
     * transaction: two rapid independent changes (tab + sort) can no longer race and restore
     * each other's previous field value.
     */
    suspend fun updateDisplayPreferences(block: (DisplayPreferences) -> DisplayPreferences) {
        storageTransaction {
            val latest = PreferenceNormalization.displayPreferences(storyStore.displayPreferences())
            val normalized = PreferenceNormalization.displayPreferences(block(latest.copy()))
            if (normalized == latest) return@storageTransaction
            storyStore.saveDisplayPreferences(normalized)
        }
    }

    /** Read-modify-write a story under the shared storage monitor; a null return from [block] aborts. */
    suspend fun updateStory(
        storyId: String,
        block: (Story?) -> Story?,
    ): Story? =
        storageTransaction {
            val current = storyForWriteLocked(storyId)
            val updated = block(current) ?: return@storageTransaction null
            saveStoryLocked(updated)
            publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
            StoryMutations.snapshot(updated)
        }

    private suspend fun updateExistingStory(
        storyId: String,
        block: (Story) -> Story?,
    ): Story? = updateStory(storyId) { latest -> latest?.let(block) }

    /** Commits only generated EPUB pointers, retaining chapters/bookmarks/sync metadata. */
    suspend fun markEpubGenerated(
        storyId: String,
        paths: List<String>,
    ): Story? = updateExistingStory(storyId) { StoryMutations.markEpubGenerated(it, paths) }

    /** Marks existing EPUB output stale after cleanup without re-saving the cleanup snapshot. */
    suspend fun markCleanupApplied(storyId: String): Story? = updateExistingStory(storyId, StoryMutations::markCleanupApplied)

    /** Toggles a chapter bookmark against the latest story and rejects chapters removed by sync. */
    suspend fun toggleBookmark(
        storyId: String,
        chapterId: String,
    ): Story? = updateExistingStory(storyId) { StoryMutations.toggleBookmark(it, chapterId) }

    suspend fun setEpubConfig(
        storyId: String,
        config: EpubConfig,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setEpubConfig(it, config) }

    /** Stores a generated AI synopsis and switches the story to display it. */
    suspend fun setAiDescription(
        storyId: String,
        description: String,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setAiDescription(it, description) }

    // Cover-art transactions live in AppRepositoryCovers.kt as extensions sharing this storage monitor.

    /** Flips which synopsis (source vs AI) the Details screen displays; no-op before one exists. */
    suspend fun setShowAiDescription(
        storyId: String,
        showAi: Boolean,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setShowAiDescription(it, showAi) }

    /** Stores the explicit AI context-chapter selection; null resets to the first downloaded chapters. */
    suspend fun setAiContextChapters(
        storyId: String,
        indices: List<Int>?,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setAiContextChapters(it, indices) }

    /** Updates references after missing EPUB files are detected, retaining unrelated story state. */
    suspend fun retainEpubPaths(
        storyId: String,
        paths: List<String>,
    ): Story? = updateExistingStory(storyId) { StoryMutations.retainEpubPaths(it, paths) }

    /** Commits one downloaded chapter against the latest post-sync chapter list. */
    suspend fun markChapterDownloaded(
        storyId: String,
        chapterId: String,
        path: String,
        completedAt: Long,
    ): Story? =
        updateExistingStory(storyId) {
            StoryMutations.markChapterDownloaded(it, chapterId, path, completedAt)
        }

    /** Outcome of [completeDownloadedChapter]; rejected commits must not surface as job failures. */
    enum class ChapterCommit {
        COMMITTED,
        SKIPPED,
        CHAPTER_MISSING,
    }

    /**
     * Commits one downloaded chapter and its queue job in a single transaction: the job
     * must still exist and be downloading, and the library generation must be unchanged, so a
     * cancel, remove, clear, or restore landing between the chapter fetch and this call can
     * never publish the chapter or flip a cancelled row to completed.
     */
    suspend fun completeDownloadedChapter(
        job: DownloadJob,
        writeChapter: () -> String,
        completedAt: Long,
        startedGeneration: Long,
    ): ChapterCommit =
        storageTransaction {
            if (startedGeneration != libraryGeneration.get()) return@storageTransaction ChapterCommit.SKIPPED
            requireLocked()
            val stillActive = queueJobs.any { it.id == job.id && it.status == DownloadJobStatus.Downloading.wire }
            if (!stillActive) return@storageTransaction ChapterCommit.SKIPPED
            val story = storyForWriteLocked(job.storyId) ?: return@storageTransaction ChapterCommit.SKIPPED
            if (story.chapters.none { it.id == job.chapter.id }) return@storageTransaction ChapterCommit.CHAPTER_MISSING
            val path = writeChapter()
            val downloaded =
                StoryMutations.markChapterDownloaded(story, job.chapter.id, path, completedAt)
                    ?: return@storageTransaction ChapterCommit.CHAPTER_MISSING
            // A downloaded public chapter retires the Patreon early-access copy it publishes.
            val (marked, retiredCopies) = PatreonCopyPlanning.reconcileStory(downloaded)
            saveStoryLocked(marked)
            retiredCopies.forEach { copy -> runCatching { requiredStorage.deleteChapterFile(marked.id, copy.filePath) } }
            // completed() returns a copy, so unchanged rows can be shared with the working queue.
            val updated =
                queueJobs.map { current ->
                    if (current.id != job.id) current else DownloadJobTransitions.completed(current) ?: current
                }
            storyStore.saveQueue(updated)
            queueJobs = updated
            publishDownloadStateLocked(libraryChanged = true, queueChanged = true)
            ChapterCommit.COMMITTED
        }

    /**
     * Commits a sync atomically with an optional metric snapshot.
     *
     * @param startedGeneration [libraryGeneration] captured when the sync began; a mismatch means
     *   the library was cleared or replaced mid-flight, so the commit is rejected.
     * @param requireExisting true when the sync started from an existing story; a missing current
     *   record then means the user deleted it during the network window and the sync must not
     *   recreate it.
     */
    suspend fun commitSyncedStory(
        story: Story,
        metricSnapshot: StoryMetricSnapshot? = null,
        startedGeneration: Long = libraryGeneration.get(),
        requireExisting: Boolean = false,
        merge: (Story?) -> Story = { story },
    ): Story =
        storageTransaction {
            check(startedGeneration == libraryGeneration.get()) {
                "Library was replaced while this sync was running; discarding its result"
            }
            val current = storyForWriteLocked(story.id)
            if (requireExisting) {
                check(current != null) { "Story ${story.id} was deleted while it was syncing; discarding the sync result" }
            }
            val committed = keepDownloadedChapters(current, merge(current))
            saveStoryLocked(committed)
            committed.patreonStats?.takeIf { metricSnapshot?.patreonRaw != null }?.let { fresh ->
                sharePatreonStatsLocked(committed, fresh)
            }
            metricSnapshot?.let { snapshot ->
                // Metrics are diagnostic; a fenced metrics write must not drop the committed story.
                runCatching { requiredStorage.appendMetricSnapshot(committed.id, snapshot) }
                    .onFailure { error -> Timber.w(error, "Failed to record metric snapshot for %s", committed.id) }
            }
            publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
            StoryMutations.snapshot(committed)
        }

    /**
     * Last line of defense for a sync commit: a downloaded chapter the stored story has and the
     * committed one lacks is put back as removed from source instead of silently disappearing.
     * Reaching this means a merge path dropped downloaded text, so it is logged as a warning.
     */
    private fun keepDownloadedChapters(
        current: Story?,
        committed: Story,
    ): Story {
        if (current == null) return committed
        val restored =
            RemovedChapterPlanning.restoreLostDownloads(
                current,
                committed,
                storyChapterKey(committed),
                System.currentTimeMillis(),
            )
        if (restored === committed) return committed
        val kept = restored.chapters.size - committed.chapters.size
        Timber.w("Sync commit would have dropped %d downloaded chapter(s) of %s; kept them", kept, committed.id)
        ActivityLog.record(
            ActivityCategory.SYNC,
            "sync_safety_net_kept_chapters",
            "storyId" to committed.id,
            "title" to committed.title,
            "kept" to kept,
            "chaptersBefore" to current.chapters.size,
            "chaptersCommitted" to committed.chapters.size,
        )
        return restored
    }

    /**
     * Patreon stats belong to the creator: a fresh refresh updates every story sharing it, unless
     * that sibling already holds a newer capture (overlapping syncs can commit out of fetch order).
     * Like the metric write, a sibling write is secondary and must not fail the committed sync.
     */
    private fun sharePatreonStatsLocked(
        committed: Story,
        fresh: PatreonRawStats,
    ) {
        PatreonCreatorPlanning.siblings(committed, libraryById.values).forEach { sibling ->
            runCatching {
                val updated = storyForWriteLocked(sibling.id) ?: return@runCatching
                val current = updated.patreonStats
                if (current != null && current.capturedAt > fresh.capturedAt) return@runCatching
                updated.patreonStats = fresh
                saveStoryLocked(updated)
            }.onFailure { error -> Timber.w(error, "Failed to share Patreon stats with %s", sibling.id) }
        }
    }

    suspend fun upsertStory(story: Story) {
        storageTransaction {
            saveStoryLocked(story)
            publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
        }
    }

    suspend fun deleteStory(id: String) {
        storageTransaction {
            requireLocked()
            libraryById[id]?.let { story ->
                ActivityLog.record(
                    ActivityCategory.LIBRARY,
                    "story_deleted",
                    "storyId" to story.id,
                    "title" to story.title,
                    "chapters" to story.chapters.size,
                    "downloaded" to story.chapters.count { it.downloaded },
                    "removedFlagged" to story.chapters.count { it.isRemovedFromSource },
                    "archived" to story.isArchived,
                )
            }
            // The store drops the story's jobs from the persisted queue too; mirror that here.
            storyStore.deleteStory(id)
            libraryById.remove(id)
            queueJobs = queueJobs.filterNot { it.storyId == id }
            publishDownloadStateLocked(libraryChanged = true, queueChanged = true)
        }
    }

    suspend fun saveLibrary(stories: List<Story>) {
        storageTransaction {
            requireLocked()
            storyStore.saveLibrary(stories)
            libraryById.clear()
            stories.map(StoryMutations::snapshot).forEach { libraryById[it.id] = it }
            publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
        }
    }

    /** Read-modify-write the queue under the shared storage monitor, the same serialization point the download engine uses. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun updateQueue(block: (List<DownloadJob>) -> List<DownloadJob>): List<DownloadJob> =
        storageTransaction {
            val startedAt = System.nanoTime() / 1_000_000L
            var failed = false
            try {
                saveQueueLocked(block(queueForWriteLocked()))
                publishDownloadStateLocked(libraryChanged = false, queueChanged = true)
                queueJobs
            } catch (error: Exception) {
                failed = true
                throw error
            } finally {
                com.vinicius741.webnovelarchiver.data.diagnostics.LocalDiagnostics.recordOperation(
                    "queue_save",
                    System.nanoTime() / 1_000_000L - startedAt,
                    failed,
                )
            }
        }

    /** Replaces the queue wholesale and refreshes the flow (used by enqueue/recovery paths). */
    suspend fun saveQueue(jobs: List<DownloadJob>) {
        storageTransaction {
            saveQueueLocked(jobs)
            publishDownloadStateLocked(libraryChanged = false, queueChanged = true)
        }
    }

    private fun snapshotJob(job: DownloadJob): DownloadJob = job.copy(chapter = job.chapter.copy())
}

/** Links (or unlinks, with null) a story's Patreon early-access collection; see [PatreonCopyPlanning.relink]. */
internal suspend fun AppRepository.setPatreonEarlyAccess(
    storyId: String,
    link: PatreonEarlyAccessLink?,
): Story? {
    var removed: List<Chapter> = emptyList()
    val updated =
        updateStory(storyId) { latest ->
            latest?.let { story -> PatreonCopyPlanning.relink(story, link).also { removed = it.second }.first }
        }
    if (updated != null && removed.isNotEmpty()) {
        withContext(Dispatchers.IO) { removed.forEach { copy -> runCatching { storage.deleteChapterFile(storyId, copy.filePath) } } }
    }
    return updated
}

/** Sets the Patreon link the user entered, or returns to the source's with null; see [PatreonCopyPlanning.withManualPatreonUrl]. */
internal suspend fun AppRepository.setManualPatreonUrl(
    storyId: String,
    url: String?,
): Story? {
    var removed: List<Chapter> = emptyList()
    val updated =
        updateStory(storyId) { latest ->
            latest?.let { story -> PatreonCopyPlanning.withManualPatreonUrl(story, url).also { removed = it.second }.first }
        }
    if (updated != null && removed.isNotEmpty()) {
        withContext(Dispatchers.IO) { removed.forEach { copy -> runCatching { storage.deleteChapterFile(storyId, copy.filePath) } } }
    }
    return updated
}

/**
 * Writes a generated cover image to storage and points the story at it. The source
 * [Story.coverUrl] is never touched, so [clearAiCover] can restore it at any time.
 */
internal suspend fun AppRepository.setAiCover(
    storyId: String,
    bytes: ByteArray,
    mediaType: String?,
): Story? =
    updateStory(storyId) { latest ->
        latest?.let { story ->
            preserveAppliedCover(story)
            val file = storage.covers.saveApplied(storyId, bytes, AiCoverPlanning.coverFileExtension(mediaType))
            LocalImageRevision.changed(file)
            StoryMutations.setAiCoverPath(story, storage.relativize(file))
        }
    }

/** Removes the generated cover file and record so the story shows its source cover again. */
internal suspend fun AppRepository.clearAiCover(storyId: String): Story? =
    updateStory(storyId) { latest ->
        latest?.let { story ->
            preserveAppliedCover(story)
            storage.covers.deleteApplied(storyId)
            StoryMutations.clearAiCover(story)
        }
    }

/** Flips which cover the app displays (AI vs source) without touching either stored image. */
internal suspend fun AppRepository.setShowAiCover(
    storyId: String,
    showAi: Boolean,
): Story? =
    updateStory(storyId) { latest ->
        latest?.let { StoryMutations.setShowAiCover(it, showAi) }
    }

/** The story's locally generated cover file, when one is recorded and present on disk. */
internal fun AppRepository.coverFile(
    story: Story,
    checkExists: Boolean = true,
): File? = storage.resolveAbsolutePath(story.aiCoverPath, checkExists)

/*
 * Pending (preview-only) AI cover drafts. Unlike the cover transactions above these do not mutate
 * library state — they persist the background generation result so it survives navigation and
 * process death until the user applies or discards it.
 */

/** Persists the staged flow's editable image prompt (stage 1 result), dropping any painted preview. */
internal suspend fun AppRepository.saveAiCoverPromptDraft(
    storyId: String,
    prompt: String,
) {
    withContext(Dispatchers.IO) { storage.covers.savePrompt(storyId, prompt) }
    aiCoverUnseenState.update { it - storyId }
}

/** Persists a painted preview (with the prompt that produced it) as the story's pending draft. */
internal suspend fun AppRepository.saveAiCoverImageDraft(
    storyId: String,
    draft: AiCoverDraft,
) {
    withContext(Dispatchers.IO) { storage.covers.saveImage(storyId, draft) }
}

/**
 * Persists a finished cover draft only when the story still exists: the existence check and
 * the draft save run as one storage transaction, so a story deleted mid-generation cannot regain
 * orphaned draft files. False = discarded.
 */
internal suspend fun AppRepository.persistAiCoverDraftIfStoryExists(
    storyId: String,
    record: AiCoverDraftRecord,
    unseen: Boolean = false,
): Boolean {
    val saved =
        storageTransaction {
            if (!containsStoryLocked(storyId)) {
                false
            } else {
                when (record) {
                    is AiCoverDraftRecord.PromptOnly -> storage.covers.savePrompt(storyId, record.prompt, unseen)
                    is AiCoverDraftRecord.Image -> storage.covers.saveImage(storyId, record.draft, unseen)
                }
                true
            }
        }
    if (saved) {
        val kind = if (record is AiCoverDraftRecord.Image) AiCoverResultKind.IMAGE else AiCoverResultKind.PROMPT
        aiCoverUnseenState.update { if (unseen) it + (storyId to kind) else it - storyId }
    }
    return saved
}

/** Records that the user looked at the story's latest background cover result. */
internal suspend fun AppRepository.markAiCoverResultSeen(storyId: String) {
    if (storyId !in aiCoverUnseenState.value) return
    withContext(Dispatchers.IO) { storage.covers.markSeen(storyId) }
    aiCoverUnseenState.update { it - storyId }
}

/** The story's persisted pending draft, or null when there is none. */
internal suspend fun AppRepository.loadAiCoverDraft(storyId: String): AiCoverDraftRecord? =
    withContext(Dispatchers.IO) { storage.covers.load(storyId) }

/** Deletes the story's pending draft; called on Apply, Discard, and AI-cover deletion. */
internal suspend fun AppRepository.deleteAiCoverDraft(storyId: String) {
    withContext(Dispatchers.IO) { storage.covers.delete(storyId) }
    aiCoverUnseenState.update { it - storyId }
}

/** Stores cover context independently from the description selection. */
internal suspend fun AppRepository.setAiCoverContextChapters(
    storyId: String,
    indices: List<Int>?,
): Story? =
    updateStory(storyId) { latest ->
        latest?.copy(aiCoverContextChapterIndices = indices?.distinct()?.sorted()?.toMutableList())
    }

/** Stores the life stage automatic covers depict (a `LifeStage.key`); null returns to Auto (the main phase). */
internal suspend fun AppRepository.setAiCoverPhase(
    storyId: String,
    stageKey: String?,
): Story? = updateStory(storyId) { latest -> latest?.copy(aiCoverPhase = stageKey) }

/** The persisted active playback session, or null when playback fully stopped. */
fun AppRepository.getTtsSession(): TtsSession? = ttsSession?.copy()

suspend fun AppRepository.saveTtsSession(session: TtsSession) {
    storageTransaction {
        storage.saveTtsSession(session)
        ttsSession = session.copy()
    }
}

suspend fun AppRepository.clearTtsSession() {
    storageTransaction {
        storage.clearTtsSession()
        ttsSession = null
    }
}

suspend fun AppRepository.getTtsStoryPosition(storyId: String): TtsStoryPosition? =
    storageTransaction { storage.getTtsStoryPositions()[storyId] }?.copy()

suspend fun AppRepository.getTtsStoryPositions(): List<TtsStoryPosition> =
    storageTransaction {
        storage.getTtsStoryPositions().values.toList()
    }

suspend fun AppRepository.saveTtsStoryPosition(position: TtsStoryPosition) {
    storageTransaction { storage.saveTtsStoryPosition(position) }
}

suspend fun AppRepository.clearTtsStoryPosition(storyId: String) {
    storageTransaction { storage.clearTtsStoryPosition(storyId) }
}

/** Preserve covers applied by older app versions before they are overwritten. */
internal fun AppRepository.preserveAppliedCover(story: Story): String? =
    coverFile(story)?.takeIf { it.isFile }?.let { file ->
        val mediaType =
            when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "image/png"
            }
        storage.covers.versions.save(story.id, AiCoverDraft("", file.readBytes(), mediaType))
    }

/** Deletes one saved AI cover version; the cover in use is never deleted. */
internal suspend fun AppRepository.deleteAiCoverVersion(
    storyId: String,
    versionId: String,
): Boolean =
    storageTransaction {
        val story = story(storyId)
        val current =
            story
                ?.takeIf(AiCoverPlanning::isAiCoverActive)
                ?.let { coverFile(it) }
                ?.takeIf { it.isFile }
                ?.let { sha256Hex(it) }
        if (current == versionId) false else storage.covers.versions.delete(storyId, versionId)
    }

internal suspend fun AppRepository.listAiCoverVersions(storyId: String) =
    withContext(Dispatchers.IO) {
        val story = story(storyId)
        storage.covers.versions.listForDisplay(
            storyId,
            story?.let { coverFile(it) },
            story?.let(AiCoverPlanning::isAiCoverActive) == true,
        )
    }
