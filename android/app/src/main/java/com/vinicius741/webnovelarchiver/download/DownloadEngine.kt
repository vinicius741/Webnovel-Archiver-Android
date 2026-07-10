package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.app.AppContainer
import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.ui.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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
 *
 * Single process loop: exactly one engine in the process owns the loop — the foreground service, which
 * constructs with [ownsProcessLoop] = true. The activity constructs with false, so its engine is a
 * control/enqueue handle only: [queue] / [resumeAll] / [retryFailed] / etc. mutate the shared queue but
 * never run a loop. Without this, both engines would each launch up to `downloadConcurrency` jobs per
 * pass against the one queue (the `running` guard is per-instance, not shared), so setting concurrency
 * to 1 still produced ~2× concurrent downloads and per-source delays weren't honored across instances.
 * The activity instead hands work to the service via `DownloadForegroundService.start`, whose single
 * loop reads the resumed/retried jobs from shared storage.
 */
class DownloadEngine(
    private val repository: AppRepository,
    private val network: NetworkClient,
    private val ownsProcessLoop: Boolean = true,
) {
    private val storage: AppStorage = repository.storage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val acceptsWorkerResults = AtomicBoolean(true)
    private val nextAllowedJobAtBySource = mutableMapOf<String, Long>()
    private var worker: Job? = null
    var onChanged: (() -> Unit)? = null
    var onProgress: ((DownloadProgress) -> Unit)? = null

    private companion object {
        /**
         * One wake signal for the single process-wide download loop. Activity-owned engines are
         * control handles only, so their resume/retry mutations must wake the service-owned loop.
         */
        val processLoopWakeSignals = Channel<Unit>(Channel.CONFLATED)

        fun wakeProcessLoop() {
            processLoopWakeSignals.trySend(Unit)
        }
    }

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
        if (plan.hasRunnableWork) {
            if (startNow) {
                start()
            } else {
                wakeProcessLoop()
            }
        }
        repository.publishDownloadState(changedStoryIds = setOf(story.id), queueChanged = true)
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
        repository.publishDownloadState(queueChanged = true)
        onChanged?.invoke()
    }

    /**
     * Explicit lifecycle (Reliability R3). [start] launches the download process loop; [pause] and
     * [stopAndCancel] halt it without losing queued work; [close] tears down the engine scope.
     *
     * Single process loop: only the owner engine (the foreground service) may start the loop. The
     * activity's engine is a control/enqueue handle — control methods ([resumeAll], [retryFailed], …)
     * mutate the shared queue and the activity separately starts the service, whose loop picks the
     * work up. A no-op here on non-owners prevents a second loop from running concurrently against
     * the one queue, which would otherwise ignore the configured concurrency cap (see class doc).
     */
    fun start() {
        // Single process loop: a non-owner engine must not launch the loop. Control mutations made by
        // the activity are already picked up by the service's loop via shared storage.
        if (!ownsProcessLoop) {
            wakeProcessLoop()
            return
        }
        if (!acceptsWorkerResults.get()) return
        if (!running.compareAndSet(false, true)) {
            wakeProcessLoop()
            return
        }
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

    /**
     * Synchronously makes the durable queue resumable before Android terminates a timed-out
     * data-sync foreground service. Rejecting worker results first prevents a late network response
     * from overwriting the recovered pending state after the timeout callback returns.
     */
    internal fun recoverAfterForegroundServiceTimeout() {
        acceptsWorkerResults.set(false)
        stopAndCancel()
        mutateQueue(DownloadForegroundServiceTimeoutPlanning::recoverQueue)
    }

    /** Releases the engine's coroutine scope. Call from the owning service's onDestroy. */
    fun close() {
        stopAndCancel()
    }

    private suspend fun processLoop() {
        while (true) {
            val settings = storage.getSettings()
            cleanupUnsupportedSourceJobs()
            val sourceSettings = storage.getSourceDownloadSettings()
            val globalDownloadSettings =
                SourceDownloadSettings(
                    concurrency = settings.downloadConcurrency,
                    delay = settings.downloadDelay,
                    delayMax = settings.downloadDelayMax,
                )
            lateinit var queue: MutableList<DownloadJob>
            lateinit var pending: List<DownloadJob>
            synchronized(storage) {
                queue = storage.getQueue()
                pending =
                    DownloadScheduler.selectEligibleJobs(
                        jobs = queue,
                        now = System.currentTimeMillis(),
                        globalSettings = globalDownloadSettings,
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
            if (pending.isNotEmpty()) {
                repository.publishDownloadState(queueChanged = true)
            }
            emitProgress(null, queue)
            if (pending.isEmpty()) {
                val sleepUntil =
                    DownloadScheduler.nextWakeUpAt(
                        jobs = queue,
                        now = System.currentTimeMillis(),
                        nextAllowedAt = nextAllowedJobAtBySource,
                        providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
                    )
                if (sleepUntil != null) {
                    sleepUntilWakeOrTimeout(sleepUntil)
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
                                globalDownloadSettings,
                                sourceSettings,
                            ).let(DownloadScheduler::randomDelayMillis)
                    if (sourceDelay > 0) nextAllowedJobAtBySource[providerName] = now + sourceDelay
                }
        }
    }

    private suspend fun sleepUntilWakeOrTimeout(sleepUntil: Long) {
        val sleepMs = (sleepUntil - System.currentTimeMillis()).coerceAtLeast(200)
        withTimeoutOrNull(sleepMs) {
            processLoopWakeSignals.receive()
        }
    }

    private fun cleanupUnsupportedSourceJobs() {
        var cleaned = false
        var changedStoryIds: Set<String> = emptySet()
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
            changedStoryIds = cleanup.affectedStoryIds.toSet()
            cleaned = true
        }
        if (cleaned) {
            repository.publishDownloadState(changedStoryIds = changedStoryIds, queueChanged = true)
            onChanged?.invoke()
        }
    }

    // E2: classifier input must be broad; cancellation is caught and rethrown first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun processJob(job: DownloadJob) {
        // Audit Rec 4: snapshot the queue once for the job-start progress emission instead of letting
        // buildProgress re-parse download_queue.json. The cancellation check below re-reads after
        // network I/O (a user can cancel during the fetch), but only once rather than twice.
        emitProgress(job, storage.getQueue())
        try {
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
            if (!acceptsWorkerResults.get()) return
            val path = storage.saveChapter(job.storyId, job.chapterIndex, job.chapter, clean)
            if (!acceptsWorkerResults.get()) return
            check(
                repository.markChapterDownloaded(
                    storyId = job.storyId,
                    chapterId = job.chapter.id,
                    path = path,
                    completedAt = System.currentTimeMillis(),
                ) != null,
            ) { "Chapter not found" }
            // Re-read the queue once after network I/O so a user cancellation issued during the fetch
            // is observed (gap 4: this previously triggered two fresh getQueue() parses).
            if (!isCancelled(job.id, storage.getQueue())) updateJob(job.id, DownloadJobStatus.Completed.wire, null)
        } catch (error: CancellationException) {
            // E2: a genuine scope/job cancellation must always propagate. A *user* pause does not
            // cancel this coroutine (pauseJob/pauseAll only flip queue status), so any
            // CancellationException here is a real teardown — re-throw so the parent is cleaned up.
            Timber.d("Download job %s cancelled (story=%s)", job.id, job.storyId)
            throw error
        } catch (error: Exception) {
            // E2: catch Exception (not Throwable) so OutOfMemoryError/StackOverflowError propagate.
            if (!isCancelled(job.id, storage.getQueue())) handleJobError(job, error)
        }
    }

    private fun updateJob(
        id: String,
        status: String,
        error: String?,
    ) {
        if (!acceptsWorkerResults.get()) return
        lateinit var queue: List<DownloadJob>
        var accepted = false
        storage.mutateQueueInPlace { current ->
            queue = current
            if (!acceptsWorkerResults.get()) return@mutateQueueInPlace current
            current.find { it.id == id }?.let {
                it.status = status
                it.error = error
                if (status == DownloadJobStatus.Completed.wire) {
                    it.errorCategory = null
                    it.errorCode = null
                    it.nextRetryAt = null
                }
            }
            accepted = true
            current
        }
        if (!accepted) return
        emitProgress(queue.find { it.id == id }, queue)
        repository.publishDownloadState(queueChanged = true)
        onChanged?.invoke()
    }

    private fun handleJobError(
        job: DownloadJob,
        error: Throwable,
    ) {
        if (!acceptsWorkerResults.get()) return
        val classified = DownloadErrorClassifier.classify(error)
        // T1: error classification decisions are otherwise invisible; log them with the cause so a
        // "Download failed" report is diagnosable. (downloadErrorCategory/code map to classified.*)
        Timber.w(
            error,
            "Download job %s failed (category=%s, code=%s, retry=%s)",
            job.id,
            classified.category,
            classified.code,
            job.retryCount,
        )
        lateinit var queue: List<DownloadJob>
        var accepted = false
        storage.mutateQueueInPlace { current ->
            queue = current
            if (!acceptsWorkerResults.get()) return@mutateQueueInPlace current
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
            accepted = true
            current
        }
        if (!accepted) return
        emitProgress(queue.find { it.id == job.id }, queue)
        repository.publishDownloadState(queueChanged = true)
        onChanged?.invoke()
    }

    private fun isCancelled(
        id: String,
        queue: List<DownloadJob>,
    ): Boolean = queue.any { it.id == id && it.status == DownloadJobStatus.Cancelled.wire }

    fun currentProgress(): DownloadProgress = buildProgress(null, storage.getQueue())

    private fun emitProgress(
        activeJob: DownloadJob?,
        queue: List<DownloadJob>,
    ) {
        onProgress?.invoke(buildProgress(activeJob, queue))
    }

    private fun buildProgress(
        activeJob: DownloadJob?,
        queue: List<DownloadJob>,
    ): DownloadProgress {
        val jobs = queue
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
