package com.vinicius741.webnovelarchiver.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of [AppStorage] (Reliability R2). All read-modify-write transactions on the library,
 * download queue, and settings go through [txMutex], so the activity and the download foreground
 * service can no longer race on `download_queue.json` or per-story files — even though both hold a
 * reference to this repository, there is exactly one [AppStorage] per process and one serialization
 * lock around every multi-step mutation.
 *
 * Exposes cached [StateFlow]s ([libraryFlow], [queueFlow], [settingsFlow]) that are refreshed after
 * each successful transaction, so screens can observe state without re-reading JSON on every render
 * (Speed S3). The low-level [storage] is still accessible to engines for direct file I/O (chapter
 * HTML, EPUB bytes) — only the stateful read-modify-write mutations are centralized here.
 */
class AppRepository(
    val storage: AppStorage,
) {
    private val txMutex = Mutex()

    private val _libraryFlow = MutableStateFlow<List<Story>>(emptyList())
    val libraryFlow: StateFlow<List<Story>> = _libraryFlow.asStateFlow()

    private val _queueFlow = MutableStateFlow<List<DownloadJob>>(emptyList())
    val queueFlow: StateFlow<List<DownloadJob>> = _queueFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(storage.getSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    /** Loads the current library + queue + settings into the state flows. Call once at startup. */
    fun refresh() {
        _libraryFlow.value = storage.getLibrary()
        _queueFlow.value = storage.getQueue()
        _settingsFlow.value = storage.getSettings()
    }

    /** Cached library snapshot; reads from memory rather than re-parsing every story JSON. */
    fun library(): List<Story> = _libraryFlow.value

    /** Cached queue snapshot. */
    fun queue(): List<DownloadJob> = _queueFlow.value

    /** Single-story lookup by id, from the cached library. */
    fun story(id: String): Story? = _libraryFlow.value.firstOrNull { it.id == id }

    // ---- Read passthroughs (no caching needed; these are small single-file reads) ----
    fun getSettings(): AppSettings = storage.getSettings()

    fun getSourceDownloadSettings() = storage.getSourceDownloadSettings()

    fun getChapterFilterSettings() = storage.getChapterFilterSettings()

    fun getDisplayPreferences() = storage.getDisplayPreferences()

    fun getTabs() = storage.getTabs()

    fun getSentenceRemovalList() = storage.getSentenceRemovalList()

    fun getRegexRules() = storage.getRegexRules()

    fun getTtsSettings() = storage.getTtsSettings()

    fun getTtsSession(): TtsSession? = storage.getTtsSession()

    fun readChapter(chapter: Chapter): String? = storage.readChapter(chapter)

    // ---- Write passthroughs (settings/config are single-document, atomic on their own) ----
    fun saveSettings(settings: AppSettings) {
        storage.saveSettings(settings)
        _settingsFlow.value = settings
    }

    fun saveSourceDownloadSettings(settings: Map<String, SourceDownloadSettings>) = storage.saveSourceDownloadSettings(settings)

    fun saveChapterFilterSettings(settings: ChapterFilterSettings) = storage.saveChapterFilterSettings(settings)

    fun saveDisplayPreferences(preferences: DisplayPreferences) = storage.saveDisplayPreferences(preferences)

    fun saveTabs(tabs: List<Tab>) = storage.saveTabs(tabs)

    fun saveSentenceRemovalList(items: List<String>) = storage.saveSentenceRemovalList(items)

    fun saveRegexRules(rules: List<RegexCleanupRule>) = storage.saveRegexRules(rules)

    fun saveTtsSettings(settings: TtsSettings) = storage.saveTtsSettings(settings)

    fun saveTtsSession(session: TtsSession) = storage.saveTtsSession(session)

    fun clearTtsSession() = storage.clearTtsSession()

    // ---- Transactional library mutations ----

    /**
     * Read-modify-write a story under [txMutex]. The block receives the current story (or null) and
     * returns the replacement; the result is persisted and the library flow is refreshed.
     */
    suspend fun updateStory(
        storyId: String,
        block: (Story?) -> Story?,
    ) {
        txMutex.withLock {
            val current = storage.getStory(storyId)
            val updated = block(current) ?: return@withLock
            storage.addOrUpdateStory(updated)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    /** Adds or replaces a story, then refreshes the library flow. */
    suspend fun upsertStory(story: Story) {
        txMutex.withLock {
            storage.addOrUpdateStory(story)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    /** Re-saves a story that was mutated in place (e.g. by an engine), then refreshes the flow. */
    suspend fun persistStory(story: Story) = upsertStory(story)

    suspend fun deleteStory(id: String) {
        txMutex.withLock {
            storage.deleteStory(id)
            _libraryFlow.value = storage.getLibrary()
            _queueFlow.value = storage.getQueue()
        }
    }

    suspend fun saveLibrary(stories: List<Story>) {
        txMutex.withLock {
            storage.saveLibrary(stories)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    // ---- Transactional queue mutations ----

    /**
     * Read-modify-write the download queue under [txMutex]. The block receives the current queue and
     * returns the new queue; the result is persisted and the queue flow is refreshed. This is the
     * single serialization point that prevents the activity and the foreground service from racing
     * on the queue file (Reliability R3).
     */
    suspend fun updateQueue(block: (List<DownloadJob>) -> List<DownloadJob>) {
        txMutex.withLock {
            val current = storage.getQueue()
            val updated = block(current)
            storage.saveQueue(updated)
            _queueFlow.value = updated
        }
    }

    /** Replaces the queue wholesale and refreshes the flow (used by enqueue/recovery paths). */
    suspend fun saveQueue(jobs: List<DownloadJob>) {
        txMutex.withLock {
            storage.saveQueue(jobs)
            _queueFlow.value = jobs
        }
    }

    /** Non-suspending snapshot read of the queue (for the foreground service's process loop). */
    fun currentQueue(): List<DownloadJob> = storage.getQueue()
}
