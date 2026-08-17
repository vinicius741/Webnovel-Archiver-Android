package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.app.AppContainer
import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Queue-based chapter downloader. Owns the download
 * process loop, per-source rate-limit bookkeeping, retry/error classification, and progress emission.
 * The repository owns durable queue state; this engine owns worker lifecycle and source fences.
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
 * never run a loop. Without this, both engines would each launch their own source lanes against the
 * one queue (the `running` guard is per-instance, not shared), so source limits and delays would not
 * be honored across instances.
 * The activity instead hands work to the service via `DownloadForegroundService.start`, whose single
 * loop reads the resumed/retried jobs from shared storage.
 */
class DownloadEngine(
    private val repository: AppRepository,
    private val network: NetworkClient,
    private val downloadPacer: DownloadRequestPacer,
    private val ownsProcessLoop: Boolean = true,
) {
    private val storage: AppStorage = repository.storage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val acceptsWorkerResults = AtomicBoolean(true)
    private val requestGateFactory = DownloadRequestGateFactory(storage, downloadPacer)
    private val sourceReliability =
        DownloadSourceReliability(storage, network, acceptsWorkerResults::get) { providerId, url ->
            onSourceBlocked?.invoke(providerId, url)
        }
    private val processLoop =
        DownloadProcessLoop(
            storage = storage,
            wakeSignals = processLoopWakeSignals,
            cleanupUnsupportedSourceJobs = ::cleanupUnsupportedSourceJobs,
            processJob = ::processJob,
            publishQueueChanged = { repository.publishDownloadState(queueChanged = true) },
            emitProgress = ::emitProgress,
            isSourceBlocked = network::isSourceBlocked,
        )
    private var worker: Job? = null
    var onProgress: ((DownloadProgress) -> Unit)? = null

    /**
     * Fired when the manual-verification circuit opens for a source mid-download. The foreground
     * service uses it to tell the user their queue is paused pending an in-app verification.
     */
    var onSourceBlocked: ((providerId: String, blockedUrl: String) -> Unit)? = null

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
    }

    fun pauseAll() = mutateQueue { DownloadQueueControlPlanning.pauseAll(it) }

    fun pauseJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.pauseJob(it, jobId) }

    fun resumeJob(jobId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.resumeJob(it, jobId) }

    fun cancelAll() = mutateQueue { DownloadQueueControlPlanning.cancelAll(it) }

    fun cancelJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.cancelJob(it, jobId) }

    fun resumeAll() = mutateQueueAndStart(DownloadQueueControlPlanning::resumeAll)

    fun clearFinished() {
        mutateQueue { jobs -> jobs.filterNot { it.status in DownloadJobStatus.terminalWires } }
    }

    fun removeJob(jobId: String) = mutateQueue { jobs -> jobs.filterNot { it.id == jobId } }

    fun retryFailed() = mutateQueueAndStart(DownloadQueueControlPlanning::retryFailed)

    fun retryJob(jobId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.retryFailedJob(it, jobId) }

    fun retryFailedForStory(storyId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.retryFailed(it, storyId) }

    /**
     * Centralized read-modify-write for the queue, routed through [AppStorage.mutateQueueInPlace]:
     * the transform runs under the storage monitor so the read and write can't be interleaved by the
     * process loop (R3 single-owner). Non-suspending and fast, so control methods invoked from UI
     * button handlers never block the main thread on a coroutine mutex.
     */
    private fun mutateQueue(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        storage.mutateQueueInPlace { transform(it) }
        repository.publishDownloadState(queueChanged = true)
    }

    private fun mutateQueueAndStart(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        mutateQueue(transform)
        start()
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
        BypassEventLog.record(BypassEventCategory.DL, "download_run_started")
        worker =
            scope.launch {
                try {
                    processLoop.run()
                } finally {
                    running.set(false)
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
        mutateQueue(DownloadForegroundServiceTimeoutHandler::recoverQueue)
    }

    /** Releases the engine's coroutine scope. Call from the owning service's onDestroy. */
    fun close() {
        stopAndCancel()
    }

    private fun cleanupUnsupportedSourceJobs() {
        var cleaned = false
        var changedStoryIds: Set<String> = emptySet()
        synchronized(storage) {
            val queue = storage.getQueue()
            val cleanup =
                DownloadQueueMaintenance.failUnsupportedSourceJobs(queue) { job ->
                    SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id
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
        }
    }

    // E2: classifier input must be broad; cancellation is caught and rethrown first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun processJob(job: DownloadJob) {
        // Audit Rec 4: snapshot the queue once for the job-start progress emission instead of letting
        // buildProgress re-parse download_queue.json. The cancellation check below re-reads after
        // network I/O (a user can cancel during the fetch), but only once rather than twice.
        emitProgress(job, storage.getQueue())
        var providerName: String? = null
        try {
            val story = storage.getStory(job.storyId) ?: error("Story not found")
            val provider = SourceRegistry.getProvider(job.sourceId, job.chapter.url) ?: error("Unsupported source")
            providerName = provider.id
            requestGateFactory.ensureJobActive(job.id)
            val requestGate = requestGateFactory.gateFor(provider.id, job)
            val preflight =
                sourceReliability.preflightSourceIfNeeded(
                    sourceId = provider.id,
                    activeJob = job,
                    queue = storage.getQueue(),
                    requestGate = requestGate,
                )
            preflight.mutation?.let(::publishQueueMutation)
            if (preflight.mutation != null) return
            // S6: use the shared cached cleanup so regexes compile once per settings change, not once
            // per chapter. Output is identical to the cleanup engine's stateless contract.
            val clean =
                CleanupEngine.shared.applyDownload(
                    provider.fetchChapterContent(
                        storyUrl = story.sourceUrl,
                        chapter = job.chapter,
                        chapterIndex = job.chapterIndex,
                        network = network,
                        requestGate = requestGate,
                    ),
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
            // E2: cancellation must propagate. Scope teardown cancels directly; a user pause/cancel
            // while this job is still in the pacer is converted to cancellation by the queue-state
            // checks above so the request never starts after the job stopped being active.
            Timber.d("Download job %s cancelled (story=%s)", job.id, job.storyId)
            throw error
        } catch (error: SourceAccessBlockedException) {
            if (!isCancelled(job.id, storage.getQueue())) {
                val mutation =
                    providerName?.let { sourceReliability.blockSourceJobs(it, error) }
                        ?: sourceReliability.handleJobError(job, error, null)
                mutation?.let(::publishQueueMutation)
            }
        } catch (error: Exception) {
            // E2: catch Exception (not Throwable) so OutOfMemoryError/StackOverflowError propagate.
            if (!isCancelled(job.id, storage.getQueue())) {
                sourceReliability.handleJobError(job, error, providerName)?.let(::publishQueueMutation)
            }
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
    }

    private fun publishQueueMutation(mutation: DownloadQueueMutation) {
        emitProgress(mutation.activeJobId?.let { id -> mutation.queue.find { it.id == id } }, mutation.queue)
        repository.publishDownloadState(queueChanged = true)
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
        val counts = queue.downloadCounts()
        val blockedEvidence =
            DownloadVerificationPlanning.blockedPendingEvidence(
                jobs = queue,
                isSourceBlocked = network::isSourceBlocked,
            ) { job -> SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id }
        return DownloadProgress(
            pending = counts.pending,
            active = counts.downloading,
            completed = counts.completed,
            failed = counts.failed,
            cancelled = counts.cancelled,
            paused = counts.paused,
            total = counts.total,
            activeTitle = activeJob?.let { "${it.storyTitle}: ${it.chapter.title}" },
            sourceBlocked = queue.count { it.errorCategory == "source_blocked" },
            blockedPending = blockedEvidence.pendingCount,
            blockedPendingUrl = blockedEvidence.sampleUrl,
        )
    }
}
