package com.vinicius741.webnovelarchiver.core

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
 */
class DownloadEngine(
    private val storage: AppStorage,
    private val network: NetworkClient,
    /**
     * Optional shared [AppRepository] (Reliability R3). When provided, every queue mutation is
     * serialized through [AppRepository.updateQueue] / [AppRepository.txMutex], so the activity and
     * the foreground service — which both hold a [DownloadEngine] — cannot interleave read-modify-
     * writes on `download_queue.json`. The process loop itself ([start]) still runs only in whichever
     * component calls it (the foreground service).
     */
    private val repository: AppRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val nextAllowedJobAtBySource = mutableMapOf<String, Long>()
    private var worker: Job? = null
    var onChanged: (() -> Unit)? = null
    var onProgress: ((DownloadProgress) -> Unit)? = null

    /**
     * Enqueues chapters for download. When [repository] is set the queue + story mutation runs under
     * the repository transaction lock (R3); otherwise it falls back to direct storage writes.
     * [startNow] launches the local process loop — the activity passes `false` and hands the runner
     * to the foreground service instead.
     */
    fun queue(story: Story, indexes: List<Int>, startNow: Boolean = true) {
        val plan = DownloadQueuePlanning.queueChapters(storage.getQueue(), story, indexes)
        if (plan.changed) {
            story.status = DownloadStatus.downloading
            story.lastUpdated = System.currentTimeMillis()
            if (repository != null) {
                kotlinx.coroutines.runBlocking { repository.upsertStory(story); repository.saveQueue(plan.jobs) }
            } else {
                storage.addOrUpdateStory(story)
                storage.saveQueue(plan.jobs)
            }
        }
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
        val terminal = setOf(DownloadJobStatus.Completed.wire, DownloadJobStatus.Failed.wire, DownloadJobStatus.Cancelled.wire)
        mutateQueue { jobs -> jobs.filterNot { it.status in terminal } }
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
     * Centralized read-modify-write for the queue. When [repository] is set the mutation runs under
     * the repository transaction lock (R3), so concurrent engines can't lose updates. The fallback
     * path is the legacy direct-storage write, preserved for tests that construct the engine without
     * a repository.
     */
    private fun mutateQueue(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        if (repository != null) {
            kotlinx.coroutines.runBlocking { repository.updateQueue(transform) }
        } else {
            storage.saveQueue(transform(storage.getQueue()))
        }
        onChanged?.invoke()
    }

    /**
     * Explicit lifecycle (Reliability R3). [start] launches the download process loop; [pause] and
     * [stopAndCancel] halt it without losing queued work; [close] tears down the engine scope.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = scope.launch {
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
            val queue = storage.getQueue()
            emitProgress(null)
            val sourceSettings = storage.getSourceDownloadSettings()
            val pending = DownloadScheduler.selectEligibleJobs(
                jobs = queue,
                now = System.currentTimeMillis(),
                globalConcurrency = settings.downloadConcurrency,
                globalDelay = settings.downloadDelay,
                sourceSettings = sourceSettings,
                activeCounts = emptyMap(),
                nextAllowedAt = nextAllowedJobAtBySource,
                providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
            )
            if (pending.isEmpty()) {
                val sleepUntil = DownloadScheduler.nextWakeUpAt(
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
            pending.forEach { it.status = DownloadJobStatus.Downloading.wire }
            storage.saveQueue(queue)
            emitProgress(null)
            pending.map { job -> scope.launch { processJob(job) } }.forEach { it.join() }
            val now = System.currentTimeMillis()
            pending
                .mapNotNull { job -> SourceRegistry.getProvider(job.chapter.url)?.name }
                .distinct()
                .forEach { providerName ->
                    val sourceDelay = DownloadScheduler.settingsFor(providerName, settings.downloadConcurrency, settings.downloadDelay, sourceSettings).delay
                    if (sourceDelay > 0) nextAllowedJobAtBySource[providerName] = now + sourceDelay
                }
        }
    }

    private fun cleanupUnsupportedSourceJobs() {
        val queue = storage.getQueue()
        val cleanup = DownloadQueueMaintenance.failUnsupportedSourceJobs(queue) { job ->
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
        onChanged?.invoke()
    }

    private suspend fun processJob(job: DownloadJob) {
        try {
            emitProgress(job)
            val story = storage.getStory(job.storyId) ?: error("Story not found")
            val provider = SourceRegistry.getProvider(job.chapter.url) ?: error("Unsupported source")
            val html = network.fetch(job.chapter.url)
            // S6: use the shared cached cleanup so regexes compile once per settings change, not once
            // per chapter. Output is identical to TextCleanup.applyDownloadCleanup.
            val clean = CleanupEngine.shared.applyDownload(provider.parseChapterContent(html), storage.getSentenceRemovalList(), storage.getRegexRules())
            val path = storage.saveChapter(story.id, job.chapterIndex, job.chapter, clean)
            val chapter = story.chapters[job.chapterIndex]
            chapter.filePath = path
            chapter.downloaded = true
            story.downloadedChapters = story.chapters.count { it.downloaded }
            story.status = if (story.downloadedChapters == story.chapters.size) DownloadStatus.completed else DownloadStatus.partial
            story.epubStale = true
            story.pendingNewChapterIds = story.pendingNewChapterIds?.filterNot { it == chapter.id }?.toMutableList()?.ifEmpty { null }
            story.lastUpdated = System.currentTimeMillis()
            storage.addOrUpdateStory(story)
            if (!isCancelled(job.id)) updateJob(job.id, DownloadJobStatus.Completed.wire, null)
        } catch (error: Throwable) {
            if (!isCancelled(job.id)) handleJobError(job, error)
        }
    }

    private fun updateJob(id: String, status: String, error: String?) {
        val queue = storage.getQueue()
        queue.find { it.id == id }?.let {
            it.status = status
            it.error = error
            if (status == DownloadJobStatus.Completed.wire) {
                it.errorCategory = null
                it.errorCode = null
                it.nextRetryAt = null
            }
        }
        storage.saveQueue(queue)
        emitProgress(queue.find { it.id == id })
        onChanged?.invoke()
    }

    private fun handleJobError(job: DownloadJob, error: Throwable) {
        val classified = DownloadErrorClassifier.classify(error)
        val queue = storage.getQueue()
        queue.find { it.id == job.id }?.let {
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
        storage.saveQueue(queue)
        emitProgress(queue.find { it.id == job.id })
        onChanged?.invoke()
    }

    private fun isCancelled(id: String): Boolean =
        storage.getQueue().any { it.id == id && it.status == DownloadJobStatus.Cancelled.wire }

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
