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
 * Queue-based chapter downloader. The repository owns durable queue state; this engine owns worker
 * lifecycle, rate limiting, and retry classification.
 *
 * Exactly one engine per process runs the loop, the foreground service, which constructs with
 * [ownsProcessLoop] = true. Activity engines are control and enqueue handles only. The `running`
 * guard is per-instance, so a second loop would race source lanes and ignore the configured limits.
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
    private val requestGateFactory = DownloadRequestGateFactory(repository, downloadPacer)
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

    /** Fired when the manual-verification circuit opens mid-download; the service tells the user the queue is paused. */
    var onSourceBlocked: ((providerId: String, blockedUrl: String) -> Unit)? = null

    private companion object {
        /** One wake signal for the process-wide loop; activity-side mutations must wake the service's loop. */
        val processLoopWakeSignals = Channel<Unit>(Channel.CONFLATED)

        fun wakeProcessLoop() {
            processLoopWakeSignals.trySend(Unit)
        }
    }

    /**
     * Enqueues chapters. The queue plan and the story's status are persisted atomically via
     * [AppStorage.saveEnqueue]. Called from UI button handlers, so no blocking on a coroutine lock.
     * Pass [startNow] = false to hand the runner to the foreground service instead of this engine.
     */
    fun queue(
        story: Story,
        indexes: List<Int>,
        startNow: Boolean = true,
    ) {
        // Hold the storage monitor across read, planning, and persistence, so a concurrent queue
        // writer can't overwrite the newer snapshot.
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

    /*
     * Queue controls. Each is a suspend operation that persists one durable transaction on the
     * repository's I/O path (R01/R02): UI and service callers launch them off the main thread, and
     * every grouped action (pause/resume/cancel/remove per story) saves + publishes exactly once.
     */

    suspend fun pauseAll() = mutateQueue { DownloadQueueControlPlanning.pauseAll(it) }

    suspend fun pauseJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.pauseJob(it, jobId) }

    suspend fun pauseStory(storyId: String) = mutateQueue { DownloadQueueControlPlanning.pauseStory(it, storyId) }

    suspend fun resumeJob(jobId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.resumeJob(it, jobId) }

    suspend fun resumeStory(storyId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.resumeStory(it, storyId) }

    suspend fun cancelAll() = mutateQueue { DownloadQueueControlPlanning.cancelAll(it) }

    suspend fun cancelJob(jobId: String) = mutateQueue { DownloadQueueControlPlanning.cancelJob(it, jobId) }

    suspend fun cancelStory(storyId: String) = mutateQueue { DownloadQueueControlPlanning.cancelStory(it, storyId) }

    suspend fun resumeAll() = mutateQueueAndStart(DownloadQueueControlPlanning::resumeAll)

    suspend fun clearFinished() {
        mutateQueue { jobs -> jobs.filterNot { it.status in DownloadJobStatus.terminalWires } }
    }

    suspend fun removeJob(jobId: String) = mutateQueue { jobs -> jobs.filterNot { it.id == jobId } }

    suspend fun removeStory(storyId: String) = mutateQueue { jobs -> jobs.filterNot { it.storyId == storyId } }

    suspend fun retryFailed() = mutateQueueAndStart(DownloadQueueControlPlanning::retryFailed)

    suspend fun retryJob(jobId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.retryFailedJob(it, jobId) }

    suspend fun retryFailedForStory(storyId: String) = mutateQueueAndStart { DownloadQueueControlPlanning.retryFailed(it, storyId) }

    /** Queue read-modify-write as one repository transaction (single save + single publish). */
    private suspend fun mutateQueue(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        repository.updateQueue(transform)
    }

    private suspend fun mutateQueueAndStart(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        mutateQueue(transform)
        start()
    }

    fun start() {
        // Only the loop owner may launch; a non-owner just wakes the service's loop.
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
     * Synchronously recovers the queue before Android terminates a timed-out data-sync service.
     * Rejects worker results first so a late response can't overwrite the recovered pending state.
     * Deliberately synchronous: the callback's grace period requires the recovery to be persisted
     * before returning.
     */
    internal fun recoverAfterForegroundServiceTimeout() {
        acceptsWorkerResults.set(false)
        stopAndCancel()
        storage.mutateQueueInPlace(DownloadForegroundServiceTimeoutHandler::recoverQueue)
        repository.publishDownloadState(queueChanged = true)
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processJob(job: DownloadJob) {
        emitProgress(job, repository.queue())
        var providerName: String? = null
        // R05: remember the library this work belongs to; a clear/restore mid-download rejects it.
        val startedGeneration = repository.libraryGeneration()
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
                    queue = repository.queue(),
                    requestGate = requestGate,
                )
            preflight.mutation?.let(::publishQueueMutation)
            if (preflight.mutation != null) return
            // Shared cached cleanup compiles the regexes once per settings change, not once per chapter.
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
            if (startedGeneration != repository.libraryGeneration()) return
            // Cancelled or removed mid-download: do not publish the chapter (R05). A paused job is
            // deliberately allowed to finish its in-flight chapter — pause keeps the work.
            if (isCancelledOrGone(job.id, repository.queue())) return
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
            // Re-read the queue after network I/O so a cancellation issued during the fetch is observed.
            if (!isCancelledOrGone(job.id, repository.queue())) updateJob(job.id, DownloadJobStatus.Completed.wire, null)
        } catch (error: CancellationException) {
            // Cancellation must propagate, from scope teardown or a user pause/cancel.
            Timber.d("Download job %s cancelled (story=%s)", job.id, job.storyId)
            throw error
        } catch (error: SourceAccessBlockedException) {
            if (!isCancelledOrGone(job.id, repository.queue())) {
                val mutation =
                    providerName?.let { sourceReliability.blockSourceJobs(it, error) }
                        ?: sourceReliability.handleJobError(job, error, null)
                mutation?.let(::publishQueueMutation)
            }
        } catch (error: Exception) {
            // Exception, not Throwable, so OutOfMemoryError and StackOverflowError propagate.
            if (!isCancelledOrGone(job.id, repository.queue())) {
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

    /**
     * A job counts as no longer publishable when it was cancelled *or removed* from the queue
     * (R05): a removed job must not reappear as a completion. A paused job stays publishable.
     */
    private fun isCancelledOrGone(
        id: String,
        queue: List<DownloadJob>,
    ): Boolean = queue.none { it.id == id } || queue.any { it.id == id && it.status == DownloadJobStatus.Cancelled.wire }

    /** Progress built from the repository's cached queue snapshot; safe from the main thread. */
    fun currentProgress(): DownloadProgress = buildProgress(null, repository.queue())

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
