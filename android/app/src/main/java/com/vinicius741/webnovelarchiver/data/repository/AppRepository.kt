package com.vinicius741.webnovelarchiver.data.repository

import android.net.Uri
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Minimal persistence contract used by repository transaction/cache tests. */
internal interface RepositoryStoryStore {
    val transactionLock: Any

    fun stories(): List<Story>

    fun story(id: String): Story?

    fun addOrUpdateStory(story: Story)

    fun deleteStory(id: String)

    fun saveLibrary(stories: List<Story>)

    fun queue(): List<DownloadJob>

    fun saveQueue(jobs: List<DownloadJob>)
}

private class AppStorageStoryStore(
    private val storage: AppStorage,
) : RepositoryStoryStore {
    override val transactionLock: Any get() = storage

    override fun stories(): List<Story> = storage.getLibrary()

    override fun story(id: String): Story? = storage.getStory(id)

    override fun addOrUpdateStory(story: Story) = storage.addOrUpdateStory(story)

    override fun deleteStory(id: String) = storage.deleteStory(id)

    override fun saveLibrary(stories: List<Story>) = storage.saveLibrary(stories)

    override fun queue(): List<DownloadJob> = storage.getQueue()

    override fun saveQueue(jobs: List<DownloadJob>) = storage.saveQueue(jobs)
}

data class DownloadUiSnapshot(
    val version: Long = 0L,
    val libraryVersion: Long = 0L,
    val queueVersion: Long = 0L,
    val library: List<Story> = emptyList(),
    val queue: List<DownloadJob> = emptyList(),
)

/**
 * Single owner of [AppStorage] (Reliability R2). All read-modify-write transactions on the library,
 * download queue, and settings synchronize on [storage], the same monitor used by [AppStorage] and
 * the download engine. The activity and foreground service therefore cannot race on
 * `download_queue.json` or per-story files, and there is one lock around every multi-step mutation.
 *
 * Exposes one coherent cached [downloadState] that is refreshed after each successful download or
 * story transaction, so screens can observe state without re-reading JSON on every render (Speed S3).
 * The low-level [storage] is still accessible to engines for direct file I/O (chapter
 * HTML, EPUB bytes) — only the stateful read-modify-write mutations are centralized here.
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

    private val transactionLock: Any get() = storyStore.transactionLock

    /** Infrastructure escape hatch; feature/UI code only sees repository operations. */
    val storage: AppStorage
        get() = checkNotNull(storageDelegate) { "This repository operation requires AppStorage" }

    private val requiredStorage: AppStorage get() = storage

    private val libraryById = linkedMapOf<String, Story>()
    private val libraryCache = MutableStateFlow<List<Story>>(emptyList())

    private val queueCache = MutableStateFlow<List<DownloadJob>>(emptyList())

    private val _downloadState = MutableStateFlow(DownloadUiSnapshot())

    /**
     * Typed process-wide download snapshot carrying the coherent library + queue state that UI
     * surfaces bind to directly.
     */
    val downloadState: StateFlow<DownloadUiSnapshot> = _downloadState.asStateFlow()

    @Volatile
    private var appSettings = AppSettings()

    @Volatile
    private var sourceDownloadSettings: Map<String, SourceDownloadSettings> = emptyMap()

    @Volatile
    private var chapterFilterSettings = ChapterFilterSettings()

    @Volatile
    private var displayPreferences = DisplayPreferences()

    @Volatile
    private var tabs: List<Tab> = emptyList()

    @Volatile
    private var sentenceRemovalList: List<String> = emptyList()

    @Volatile
    private var regexRules: List<RegexCleanupRule> = emptyList()

    @Volatile
    private var ttsSettings = TtsSettings()

    @Volatile
    private var ttsSession: TtsSession? = null

    @Volatile
    private var updateFollowedStoryIds: List<String> = emptyList()

    /** Loads the current library + queue + settings into the state flows. Call once at startup. */
    fun refresh() {
        synchronized(transactionLock) {
            val storage = requiredStorage
            val library = storyStore.stories().map(StoryMutations::snapshot)
            val queue = storyStore.queue().map(::snapshotJob)
            libraryById.clear()
            library.forEach { libraryById[it.id] = it }
            libraryCache.value = library
            queueCache.value = queue
            appSettings = storage.getSettings()
            sourceDownloadSettings = storage.getSourceDownloadSettings().toMap()
            chapterFilterSettings = storage.getChapterFilterSettings()
            displayPreferences = storage.getDisplayPreferences().copy()
            tabs = storage.getTabs().map { it.copy() }
            sentenceRemovalList = storage.getSentenceRemovalList().toList()
            regexRules = storage.getRegexRules().map { it.copy() }
            ttsSettings = storage.getTtsSettings().copy()
            ttsSession = storage.getTtsSession()?.copy()
            updateFollowedStoryIds = storage.getUpdateFollowedStoryIds().toList()
            _downloadState.value = _downloadState.value.copy(library = library, queue = queue)
        }
    }

    /**
     * Publishes download-owned storage changes as one coherent observable update. The foreground
     * service and Activity share this repository, so every visible screen receives the same event
     * immediately without timers or cross-component callbacks.
     */
    internal fun publishDownloadState(
        changedStoryIds: Set<String> = emptySet(),
        queueChanged: Boolean,
    ) {
        synchronized(transactionLock) {
            changedStoryIds.forEach { storyId ->
                val story = storyStore.story(storyId)
                if (story == null) {
                    libraryById.remove(storyId)
                } else {
                    libraryById[storyId] = StoryMutations.snapshot(story)
                }
            }
            if (changedStoryIds.isNotEmpty()) libraryCache.value = libraryById.values.toList()
            if (queueChanged) queueCache.value = storyStore.queue().map(::snapshotJob)
            publishDownloadStateLocked(
                libraryChanged = changedStoryIds.isNotEmpty(),
                queueChanged = queueChanged,
            )
        }
    }

    private fun publishDownloadStateLocked(
        libraryChanged: Boolean,
        queueChanged: Boolean,
    ) {
        val previous = _downloadState.value
        val library = libraryCache.value
        val queue = queueCache.value
        val version = previous.version + 1L
        _downloadState.value =
            DownloadUiSnapshot(
                version = version,
                libraryVersion = if (libraryChanged) previous.libraryVersion + 1L else previous.libraryVersion,
                queueVersion = if (queueChanged) previous.queueVersion + 1L else previous.queueVersion,
                library = library,
                queue = queue,
            )
    }

    /**
     * Publishes one committed story without re-parsing every story file. A detached copy prevents a
     * caller that still holds the persisted instance from mutating StateFlow state in place.
     */
    private fun publishStoryLocked(story: Story) {
        val published = StoryMutations.snapshot(story)
        libraryById[published.id] = published
        val library = libraryById.values.toList()
        libraryCache.value = library
        publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
    }

    /** Cached library snapshot; reads from memory rather than re-parsing every story JSON. */
    fun library(): List<Story> = libraryCache.value.map(StoryMutations::snapshot)

    /** Cached queue snapshot. */
    fun queue(): List<DownloadJob> = queueCache.value.map(::snapshotJob)

    /** Single-story lookup by id, from the cached library. */
    fun story(id: String): Story? = synchronized(transactionLock) { libraryById[id]?.let(StoryMutations::snapshot) }

    /** Adds or replaces one story without rebuilding/re-parsing the rest of the library. */
    suspend fun addOrUpdateStory(story: Story) = upsertStory(story)

    // ---- In-memory configuration snapshots (hydrated by refresh) ----
    fun getSettings(): AppSettings = appSettings.copy()

    fun getSourceDownloadSettings(): Map<String, SourceDownloadSettings> = sourceDownloadSettings.toMap()

    fun getChapterFilterSettings(): ChapterFilterSettings = chapterFilterSettings.copy()

    fun getDisplayPreferences(): DisplayPreferences = displayPreferences.copy()

    fun getTabs(): List<Tab> = tabs.map { it.copy() }

    fun getSentenceRemovalList(): List<String> = sentenceRemovalList.toList()

    fun getRegexRules(): List<RegexCleanupRule> = regexRules.map { it.copy() }

    fun getTtsSettings(): TtsSettings = ttsSettings.copy()

    fun getTtsSession(): TtsSession? = ttsSession?.copy()

    fun getUpdateFollowedStoryIds(): List<String> = updateFollowedStoryIds.toList()

    /** Current in-memory storage health reported by the persistence layer. */
    fun getStorageHealth(): StorageHealthSnapshot = requiredStorage.storageHealth.value

    suspend fun readChapter(chapter: Chapter): String? = withContext(ioDispatcher) { requiredStorage.readChapter(chapter) }

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

    suspend fun exportBackup(): File = withContext(ioDispatcher) { requiredStorage.exportBackup() }

    suspend fun exportCleanupRules(): File = withContext(ioDispatcher) { requiredStorage.exportCleanupRules() }

    suspend fun exportFullBackup(): File = withContext(ioDispatcher) { requiredStorage.exportFullBackup() }

    suspend fun importBackupUri(uri: Uri): String =
        withContext(ioDispatcher) {
            requiredStorage.importBackupUri(uri).also { refresh() }
        }

    suspend fun importFullBackupUri(uri: Uri): String =
        withContext(ioDispatcher) {
            requiredStorage.importFullBackupUri(uri).also { refresh() }
        }

    suspend fun clearAll() {
        withContext(ioDispatcher) {
            requiredStorage.clearAll()
            refresh()
        }
    }

    // ---- Configuration writes (serialized on the injected I/O dispatcher) ----
    suspend fun saveSettings(settings: AppSettings) =
        withContext(ioDispatcher) {
            val normalized = PreferenceNormalization.appSettings(settings)
            synchronized(transactionLock) {
                requiredStorage.saveSettings(normalized)
                appSettings = normalized.copy()
            }
        }

    suspend fun saveSourceDownloadSettings(settings: Map<String, SourceDownloadSettings>) =
        withContext(ioDispatcher) {
            val normalized = PreferenceNormalization.sourceDownloadSettings(settings)
            synchronized(transactionLock) {
                requiredStorage.saveSourceDownloadSettings(normalized)
                sourceDownloadSettings = normalized.toMap()
            }
        }

    suspend fun saveChapterFilterSettings(settings: ChapterFilterSettings) =
        withContext(ioDispatcher) {
            val normalized = PreferenceNormalization.chapterFilterSettings(settings)
            synchronized(transactionLock) {
                requiredStorage.saveChapterFilterSettings(normalized)
                chapterFilterSettings = normalized.copy()
            }
        }

    suspend fun saveDisplayPreferences(preferences: DisplayPreferences) =
        withContext(ioDispatcher) {
            val normalized = PreferenceNormalization.displayPreferences(preferences)
            synchronized(transactionLock) {
                requiredStorage.saveDisplayPreferences(normalized)
                displayPreferences = normalized.copy()
            }
        }

    suspend fun saveTabs(updated: List<Tab>) =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                requiredStorage.saveTabs(updated)
                tabs = updated.sortedBy { it.order }.map { it.copy() }
            }
        }

    suspend fun saveSentenceRemovalList(items: List<String>) =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                requiredStorage.saveSentenceRemovalList(items)
                sentenceRemovalList = items.toList()
            }
        }

    suspend fun saveRegexRules(updated: List<RegexCleanupRule>) =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                requiredStorage.saveRegexRules(updated)
                regexRules = updated.map { it.copy() }
            }
        }

    suspend fun saveTtsSettings(settings: TtsSettings) =
        withContext(ioDispatcher) {
            val normalized = PreferenceNormalization.ttsSettings(settings)
            synchronized(transactionLock) {
                requiredStorage.saveTtsSettings(normalized)
                ttsSettings = normalized.copy()
            }
        }

    suspend fun saveTtsSession(session: TtsSession) =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                requiredStorage.saveTtsSession(session)
                ttsSession = session.copy()
            }
        }

    suspend fun clearTtsSession() =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                requiredStorage.clearTtsSession()
                ttsSession = null
            }
        }

    suspend fun saveUpdateFollowedStoryIds(ids: List<String>) =
        withContext(ioDispatcher) {
            val normalized = ids.filter(String::isNotBlank).distinct()
            synchronized(transactionLock) {
                requiredStorage.saveUpdateFollowedStoryIds(normalized)
                updateFollowedStoryIds = normalized
            }
        }

    // ---- Transactional library mutations ----

    /**
     * Read-modify-write a story under the shared [storage] monitor. The block receives the current
     * story (or null) and returns the replacement; the result is persisted and the flow refreshed.
     */
    suspend fun updateStory(
        storyId: String,
        block: (Story?) -> Story?,
    ): Story? =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                val current = storyStore.story(storyId)
                val updated = block(current) ?: return@synchronized null
                storyStore.addOrUpdateStory(updated)
                publishStoryLocked(updated)
                StoryMutations.snapshot(updated)
            }
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

    /** Sets a chapter bookmark against the latest story (used by automatic reading progress). */
    suspend fun setBookmark(
        storyId: String,
        chapterId: String,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setBookmark(it, chapterId) }

    /** Records last-read chapter progress and publishes the updated story to cached flows. */
    suspend fun setLastReadChapter(
        storyId: String,
        chapterId: String,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setLastReadChapter(it, chapterId) }

    suspend fun setEpubConfig(
        storyId: String,
        config: EpubConfig,
    ): Story? = updateExistingStory(storyId) { StoryMutations.setEpubConfig(it, config) }

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

    /** Commits a sync atomically with optional archive and metric snapshots. */
    suspend fun commitSyncedStory(
        story: Story,
        archiveSource: Story? = null,
        archiveReason: String = ArchiveSnapshotPlanning.SOURCE_CHAPTERS_REMOVED_REASON,
        metricSnapshot: StoryMetricSnapshot? = null,
        merge: (Story?) -> Story = { story },
    ): Story =
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                val committed = merge(storyStore.story(story.id))
                val archive =
                    archiveSource?.let { source ->
                        ArchiveSnapshotPlanning.buildArchiveSnapshot(
                            source,
                            System.currentTimeMillis(),
                            archiveReason,
                        ) { archiveId, index, chapter ->
                            requiredStorage.copyChapterToStory(archiveId, index, chapter)
                        }
                    }
                archive?.let {
                    storyStore.addOrUpdateStory(it)
                    libraryById[it.id] = StoryMutations.snapshot(it)
                }
                storyStore.addOrUpdateStory(committed)
                libraryById[committed.id] = StoryMutations.snapshot(committed)
                metricSnapshot?.let { snapshot ->
                    // Metrics are diagnostic/history data. A fenced metrics document must not make
                    // the already-committed story disappear from the library after a successful sync.
                    runCatching { requiredStorage.appendMetricSnapshot(committed.id, snapshot) }
                        .onFailure { error -> Timber.w(error, "Failed to record metric snapshot for %s", committed.id) }
                }
                libraryCache.value = libraryById.values.toList()
                publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
                StoryMutations.snapshot(committed)
            }
        }

    /** Adds or replaces a story, then refreshes the library flow. */
    suspend fun upsertStory(story: Story) {
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                storyStore.addOrUpdateStory(story)
                publishStoryLocked(story)
            }
        }
    }

    suspend fun deleteStory(id: String) {
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                storyStore.deleteStory(id)
                libraryById.remove(id)
                libraryCache.value = libraryById.values.toList()
                queueCache.value = storyStore.queue().map(::snapshotJob)
                publishDownloadStateLocked(libraryChanged = true, queueChanged = true)
            }
        }
    }

    suspend fun saveLibrary(stories: List<Story>) {
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                storyStore.saveLibrary(stories)
                libraryById.clear()
                stories.map(StoryMutations::snapshot).forEach { libraryById[it.id] = it }
                libraryCache.value = libraryById.values.toList()
                publishDownloadStateLocked(libraryChanged = true, queueChanged = false)
            }
        }
    }

    // ---- Transactional queue mutations ----

    /**
     * Read-modify-write the queue under the shared [storage] monitor. This is the same serialization
     * point used by the download engine and prevents cross-component queue races (Reliability R3).
     */
    suspend fun updateQueue(block: (List<DownloadJob>) -> List<DownloadJob>) {
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                val current = storyStore.queue()
                val updated = block(current)
                storyStore.saveQueue(updated)
                queueCache.value = updated.map(::snapshotJob)
                publishDownloadStateLocked(libraryChanged = false, queueChanged = true)
            }
        }
    }

    /** Replaces the queue wholesale and refreshes the flow (used by enqueue/recovery paths). */
    suspend fun saveQueue(jobs: List<DownloadJob>) {
        withContext(ioDispatcher) {
            synchronized(transactionLock) {
                storyStore.saveQueue(jobs)
                queueCache.value = jobs.map(::snapshotJob)
                publishDownloadStateLocked(libraryChanged = false, queueChanged = true)
            }
        }
    }

    /**
     * Direct disk read of the queue for worker loops that must not depend on StateFlow equality.
     * UI code should observe [queue] / [downloadState] instead so it stays coherent with publishes.
     */
    internal fun readQueueFromDiskForWorker(): List<DownloadJob> = storyStore.queue()

    private fun snapshotJob(job: DownloadJob): DownloadJob = job.copy(chapter = job.chapter.copy())
}
