package com.vinicius741.webnovelarchiver.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Queue-based chapter downloader (Maintainability M1: split out of Engines.kt). Owns the download
 * process loop, per-source rate-limit bookkeeping, retry/error classification, and progress emission.
 * See the class doc in Engines.kt for the R3 single-owner + lifecycle notes.
 *
 * R3 single-owner serialization: every queue read-modify-write goes through [AppStorage.mutateQueueInPlace]
 * / [AppStorage.saveEnqueue], which hold the storage monitor across the whole RMW. The activity and the
 * foreground service both hold a [DownloadEngine] over the *one* [AppStorage] from [AppContainer], so
 * they can't interleave read-modify-writes on `download_queue.json` — without ever blocking the main
 * thread on a coroutine mutex (the earlier [runBlocking] path was an ANR risk from UI button handlers).
 * The process loop itself ([start]) runs only in whichever component calls it (the foreground service).
 */
class DownloadEngine(
    private val storage: AppStorage,
    private val network: NetworkClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val nextAllowedJobAtBySource = mutableMapOf<String, Long>()
    private var worker: Job? = null
    var onChanged: (() -> Unit)? = null
    var onProgress: ((DownloadProgress) -> Unit)? = null

    /**
     * Enqueues chapters for download. The queue plan + the story's status/lastUpdated are persisted
     * together atomically via [AppStorage.saveEnqueue] (one storage-monitor acquisition), so a
     * concurrent queue writer can't interleave and lose the enqueue (R3 single-owner). No
     * [runBlocking] on the repository mutex — control methods like this are called from UI button
     * handlers, so they must not block the main thread on a contended coroutine lock.
     * [startNow] launches the local process loop — the activity passes `false` and hands the runner
     * to the foreground service instead.
     */
    fun queue(
        story: Story,
        indexes: List<Int>,
        startNow: Boolean = true,
    ) {
        // Hold the same storage monitor across read, planning, and persistence. Enqueue can run on a
        // process scope while the service updates job statuses, so synchronizing only saveEnqueue's
        // final write would allow either side to overwrite the other's newer queue snapshot.
        val plan =
            DownloadEnqueueTransaction.execute(
                lock = storage,
                story = story,
                indexes = indexes,
                now = System.currentTimeMillis(),
                readQueue = storage::getQueue,
                readStory = storage::getStory,
                persist = storage::saveEnqueue,
            )
        if (startNow && plan.hasRunnableWork) start()
        onChanged?.invoke()
    }

    fun pauseAll() = mutateQueue { DownloadQueueControlPlanning.pauseAll(it) }

    fun pauseJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.pauseJob(it, jobId) }

    fun resumeJob(jobId: String) {
        mutateQueue { DownloadQueueControlPlanning.resumeJob(it, jobId) }
        start()
        onChanged?.invoke()
    }

    fun cancelAll() = mutateQueue { DownloadQueueControlPlanning.cancelAll(it) }

    fun cancelJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.cancelJob(it, jobId) }

    fun resumeAll() {
        mutateQueue { DownloadQueueControlPlanning.resumeAll(it) }
        start()
        onChanged?.invoke()
    }

    fun clearFinished() {
        mutateQueue { jobs -> jobs.filterNot { it.status in DownloadJobStatus.terminalWires } }
    }

    fun removeJob(jobId: String) = mutateQueue { jobs -> jobs.filterNot { it.id == jobId } }

    fun retryFailed() {
        mutateQueue { DownloadQueueControlPlanning.retryFailed(it) }
        start()
        onChanged?.invoke()
    }

    fun retryJob(jobId: String) {
        mutateQueue { DownloadQueueControlPlanning.retryFailedJob(it, jobId) }
        start()
        onChanged?.invoke()
    }

    fun retryFailedForStory(storyId: String) {
        mutateQueue { DownloadQueueControlPlanning.retryFailed(it, storyId) }
        start()
        onChanged?.invoke()
    }

    /**
     * Centralized read-modify-write for the queue, routed through [AppStorage.mutateQueueInPlace]:
     * the transform runs under the storage monitor so the read and write can't be interleaved by the
     * process loop (R3 single-owner). Non-suspending and fast, so control methods invoked from UI
     * button handlers never block the main thread on a coroutine mutex.
     */
    private fun mutateQueue(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        storage.mutateQueueInPlace { transform(it) }
        onChanged?.invoke()
    }

    /**
     * Explicit lifecycle (Reliability R3). [start] launches the download process loop; [pause] and
     * [stopAndCancel] halt it without losing queued work; [close] tears down the engine scope.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker =
            scope.launch {
                try {
                    processLoop()
                } finally {
                    running.set(false)
                    onChanged?.invoke()
                }
            }
    }

    /** Pauses the process loop without mutating queue state (the service's ACTION_STOP pauses jobs). */
    fun pause() {
        worker?.cancel()
        worker = null
        running.set(false)
    }

    /** Cancels the process loop and the engine scope, abandoning in-flight jobs (recover on restart). */
    fun stopAndCancel() {
        pause()
        scope.coroutineContext[Job]?.cancelChildren()
    }

    /** Releases the engine's coroutine scope. Call from the owning service's onDestroy. */
    fun close() {
        stopAndCancel()
    }

    private suspend fun processLoop() {
        val settings = storage.getSettings()
        while (true) {
            cleanupUnsupportedSourceJobs()
            val sourceSettings = storage.getSourceDownloadSettings()
            lateinit var queue: MutableList<DownloadJob>
            lateinit var pending: List<DownloadJob>
            synchronized(storage) {
                queue = storage.getQueue()
                pending =
                    DownloadScheduler.selectEligibleJobs(
                        jobs = queue,
                        now = System.currentTimeMillis(),
                        globalConcurrency = settings.downloadConcurrency,
                        globalDelay = settings.downloadDelay,
                        sourceSettings = sourceSettings,
                        activeCounts = emptyMap(),
                        nextAllowedAt = nextAllowedJobAtBySource,
                        providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
                    )
                if (pending.isNotEmpty()) {
                    pending.forEach { it.status = DownloadJobStatus.Downloading.wire }
                    storage.saveQueue(queue)
                }
            }
            emitProgress(null)
            if (pending.isEmpty()) {
                val sleepUntil =
                    DownloadScheduler.nextWakeUpAt(
                        jobs = queue,
                        now = System.currentTimeMillis(),
                        nextAllowedAt = nextAllowedJobAtBySource,
                        providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
                    )
                if (sleepUntil != null) {
                    delay((sleepUntil - System.currentTimeMillis()).coerceAtLeast(200))
                    continue
                }
                break
            }
            pending.map { job -> scope.launch { processJob(job) } }.forEach { it.join() }
            val now = System.currentTimeMillis()
            pending
                .mapNotNull { job -> SourceRegistry.getProvider(job.chapter.url)?.name }
                .distinct()
                .forEach { providerName ->
                    val sourceDelay =
                        DownloadScheduler
                            .settingsFor(
                                providerName,
                                settings.downloadConcurrency,
                                settings.downloadDelay,
                                sourceSettings,
                            ).delay
                    if (sourceDelay > 0) nextAllowedJobAtBySource[providerName] = now + sourceDelay
                }
        }
    }

    private fun cleanupUnsupportedSourceJobs() {
        var cleaned = false
        synchronized(storage) {
            val queue = storage.getQueue()
            val cleanup =
                DownloadQueueMaintenance.failUnsupportedSourceJobs(queue) { job ->
                    SourceRegistry.getProvider(job.chapter.url)?.name
                }
            if (cleanup.cleanedJobCount == 0) return
            storage.saveQueue(queue)
            cleanup.affectedStoryIds.forEach { storyId ->
                val story = storage.getStory(storyId) ?: return@forEach
                if (DownloadQueueMaintenance.recoverStuckDownloadingStory(story, queue.filter { it.storyId == storyId })) {
                    storage.addOrUpdateStory(story)
                }
            }
            cleaned = true
        }
        if (cleaned) onChanged?.invoke()
    }

    private suspend fun processJob(job: DownloadJob) {
        try {
            emitProgress(job)
            storage.getStory(job.storyId) ?: error("Story not found")
            val provider = SourceRegistry.getProvider(job.chapter.url) ?: error("Unsupported source")
            val html = network.fetch(job.chapter.url)
            // S6: use the shared cached cleanup so regexes compile once per settings change, not once
            // per chapter. Output is identical to TextCleanup.applyDownloadCleanup.
            val clean =
                CleanupEngine.shared.applyDownload(
                    provider.parseChapterContent(html),
                    storage.getSentenceRemovalList(),
                    storage.getRegexRules(),
                )
            val path = storage.saveChapter(job.storyId, job.chapterIndex, job.chapter, clean)
            synchronized(storage) {
                // Re-read after network I/O so sync/enqueue changes made while fetching are retained.
                val story = storage.getStory(job.storyId) ?: error("Story not found")
                val chapter = story.chapters.getOrNull(job.chapterIndex) ?: error("Chapter not found")
                chapter.filePath = path
                chapter.downloaded = true
                story.downloadedChapters = story.chapters.count { it.downloaded }
                story.status = if (story.downloadedChapters == story.chapters.size) DownloadStatus.completed else DownloadStatus.partial
                story.epubStale = true
                story.pendingNewChapterIds =
                    story.pendingNewChapterIds
                        ?.filterNot { it == chapter.id }
                        ?.toMutableList()
                        ?.ifEmpty { null }
                story.lastUpdated = System.currentTimeMillis()
                storage.addOrUpdateStory(story)
            }
            if (!isCancelled(job.id)) updateJob(job.id, DownloadJobStatus.Completed.wire, null)
        } catch (error: Throwable) {
            if (error is CancellationException && isPaused(job.id)) return
            if (!isCancelled(job.id)) handleJobError(job, error)
        }
    }

    private fun updateJob(
        id: String,
        status: String,
        error: String?,
    ) {
        lateinit var queue: List<DownloadJob>
        storage.mutateQueueInPlace { current ->
            current.find { it.id == id }?.let {
                it.status = status
                it.error = error
                if (status == DownloadJobStatus.Completed.wire) {
                    it.errorCategory = null
                    it.errorCode = null
                    it.nextRetryAt = null
                }
            }
            queue = current
            current
        }
        emitProgress(queue.find { it.id == id })
        onChanged?.invoke()
    }

    private fun handleJobError(
        job: DownloadJob,
        error: Throwable,
    ) {
        val classified = DownloadErrorClassifier.classify(error)
        lateinit var queue: List<DownloadJob>
        storage.mutateQueueInPlace { current ->
            current.find { it.id == job.id }?.let {
                it.retryCount += 1
                it.error = classified.message
                it.errorCategory = classified.category
                it.errorCode = classified.code
                if (DownloadErrorClassifier.shouldAutoRetry(it, classified)) {
                    it.status = DownloadJobStatus.Pending.wire
                    it.nextRetryAt = System.currentTimeMillis() + DownloadErrorClassifier.retryDelayMs(it)
                } else {
                    it.status = if (classified.category == "cancelled") DownloadJobStatus.Cancelled.wire else DownloadJobStatus.Failed.wire
                    it.nextRetryAt = null
                }
            }
            queue = current
            current
        }
        emitProgress(queue.find { it.id == job.id })
        onChanged?.invoke()
    }

    private fun isCancelled(id: String): Boolean = storage.getQueue().any { it.id == id && it.status == DownloadJobStatus.Cancelled.wire }

    private fun isPaused(id: String): Boolean = storage.getQueue().any { it.id == id && it.status == DownloadJobStatus.Paused.wire }

    fun currentProgress(): DownloadProgress = buildProgress(null)

    private fun emitProgress(activeJob: DownloadJob?) {
        onProgress?.invoke(buildProgress(activeJob))
    }

    private fun buildProgress(activeJob: DownloadJob?): DownloadProgress {
        val jobs = storage.getQueue()
        return DownloadProgress(
            pending = jobs.count { it.status == DownloadJobStatus.Pending.wire },
            active = jobs.count { it.status == DownloadJobStatus.Downloading.wire },
            completed = jobs.count { it.status == DownloadJobStatus.Completed.wire },
            failed = jobs.count { it.status == DownloadJobStatus.Failed.wire },
            cancelled = jobs.count { it.status == DownloadJobStatus.Cancelled.wire },
            paused = jobs.count { it.status == DownloadJobStatus.Paused.wire },
            total = jobs.size,
            activeTitle = activeJob?.let { "${it.storyTitle}: ${it.chapter.title}" },
        )
    }
}
