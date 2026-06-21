package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of [AppStorage] (Reliability R2). All read-modify-write transactions on the library,
 * download queue, and settings synchronize on [storage], the same monitor used by [AppStorage] and
 * the download engine. The activity and foreground service therefore cannot race on
 * `download_queue.json` or per-story files, and there is one lock around every multi-step mutation.
 *
 * Exposes cached [StateFlow]s ([libraryFlow], [queueFlow], [settingsFlow]) that are refreshed after
 * each successful transaction, so screens can observe state without re-reading JSON on every render
 * (Speed S3). The low-level [storage] is still accessible to engines for direct file I/O (chapter
 * HTML, EPUB bytes) — only the stateful read-modify-write mutations are centralized here.
 */
class AppRepository(
    val storage: AppStorage,
) {
    private val _libraryFlow = MutableStateFlow<List<Story>>(emptyList())
    val libraryFlow: StateFlow<List<Story>> = _libraryFlow.asStateFlow()

    private val _queueFlow = MutableStateFlow<List<DownloadJob>>(emptyList())
    val queueFlow: StateFlow<List<DownloadJob>> = _queueFlow.asStateFlow()

    private val _downloadStateVersion = MutableStateFlow(0L)

    /**
     * Monotonic, process-wide signal emitted after a coherent download state snapshot has been
     * published to [libraryFlow] and/or [queueFlow]. Screens observe this instead of polling JSON.
     * A version is used rather than relying on model equality because queue/story models contain
     * mutable fields and may be updated in place by the download engine.
     */
    val downloadStateVersion: StateFlow<Long> = _downloadStateVersion.asStateFlow()

    private val _settingsFlow = MutableStateFlow(storage.getSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    /** Loads the current library + queue + settings into the state flows. Call once at startup. */
    fun refresh() {
        _libraryFlow.value = storage.getLibrary()
        _queueFlow.value = storage.getQueue()
        _settingsFlow.value = storage.getSettings()
    }

    /**
     * Publishes download-owned storage changes as one coherent observable update. The foreground
     * service and Activity share this repository, so every visible screen receives the same event
     * immediately without timers or cross-component callbacks.
     */
    internal fun publishDownloadState(
        libraryChanged: Boolean,
        queueChanged: Boolean,
    ) {
        synchronized(storage) {
            if (libraryChanged) _libraryFlow.value = storage.getLibrary()
            if (queueChanged) _queueFlow.value = storage.getQueue()
            _downloadStateVersion.value = _downloadStateVersion.value + 1L
        }
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
     * Read-modify-write a story under the shared [storage] monitor. The block receives the current
     * story (or null) and returns the replacement; the result is persisted and the flow refreshed.
     */
    suspend fun updateStory(
        storyId: String,
        block: (Story?) -> Story?,
    ) {
        synchronized(storage) {
            val current = storage.getStory(storyId)
            val updated = block(current) ?: return@synchronized
            storage.addOrUpdateStory(updated)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    /** Adds or replaces a story, then refreshes the library flow. */
    suspend fun upsertStory(story: Story) {
        synchronized(storage) {
            storage.addOrUpdateStory(story)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    /** Re-saves a story that was mutated in place (e.g. by an engine), then refreshes the flow. */
    suspend fun persistStory(story: Story) = upsertStory(story)

    suspend fun deleteStory(id: String) {
        synchronized(storage) {
            storage.deleteStory(id)
            _libraryFlow.value = storage.getLibrary()
            _queueFlow.value = storage.getQueue()
        }
    }

    suspend fun saveLibrary(stories: List<Story>) {
        synchronized(storage) {
            storage.saveLibrary(stories)
            _libraryFlow.value = storage.getLibrary()
        }
    }

    // ---- Transactional queue mutations ----

    /**
     * Read-modify-write the queue under the shared [storage] monitor. This is the same serialization
     * point used by the download engine and prevents cross-component queue races (Reliability R3).
     */
    suspend fun updateQueue(block: (List<DownloadJob>) -> List<DownloadJob>) {
        synchronized(storage) {
            val current = storage.getQueue()
            val updated = block(current)
            storage.saveQueue(updated)
            _queueFlow.value = updated
        }
    }

    /** Replaces the queue wholesale and refreshes the flow (used by enqueue/recovery paths). */
    suspend fun saveQueue(jobs: List<DownloadJob>) {
        synchronized(storage) {
            storage.saveQueue(jobs)
            _queueFlow.value = jobs
        }
    }

    /** Non-suspending snapshot read of the queue (for the foreground service's process loop). */
    fun currentQueue(): List<DownloadJob> = storage.getQueue()
}
