package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Completion-driven coordinator for fair, independent source download lanes. */
@Suppress("LongParameterList")
internal class DownloadProcessLoop(
    private val storage: AppStorage,
    private val wakeSignals: ReceiveChannel<Unit>,
    private val cleanupUnsupportedSourceJobs: () -> Unit,
    private val processJob: suspend (DownloadJob) -> Unit,
    private val publishQueueChanged: () -> Unit,
    private val emitProgress: (DownloadJob?, List<DownloadJob>) -> Unit,
    private val isSourceBlocked: (String) -> Boolean = { false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class ActiveWorker(
        val sourceId: String,
    )

    suspend fun run() =
        supervisorScope {
            val workerCompletions = Channel<String>(Channel.UNLIMITED)
            val activeWorkers = mutableMapOf<String, ActiveWorker>()
            var lastScheduledSource: String? = null

            while (true) {
                val settings = storage.getSettings()
                cleanupUnsupportedSourceJobs()
                lateinit var queue: MutableList<DownloadJob>
                lateinit var pending: List<DownloadJob>
                synchronized(storage) {
                    queue = storage.getQueue()
                    val now = nowMillis()
                    val activeCounts = activeWorkers.values.groupingBy(ActiveWorker::sourceId).eachCount()
                    // Sources under manual verification keep their jobs pending; without this the
                    // loop would start each one, hit the open circuit, and fail it without any
                    // network attempt.
                    val blockedSources =
                        queue
                            .asSequence()
                            .filter { it.status == DownloadJobStatus.Pending.wire }
                            .mapNotNull(::sourceIdForJob)
                            .toSet()
                            .filter(isSourceBlocked)
                            .toSet()
                    // Re-defer blocked jobs whose recheck elapsed so the loop keeps a wake-up while
                    // the circuit is open; when verification succeeds the deferral simply expires
                    // and the queue resumes. Without this, the passed recheck time would look like
                    // no scheduled work and the loop would exit with jobs stranded as pending.
                    var redeferredBlockedJobs = false
                    queue.forEach { job ->
                        val source = sourceIdForJob(job) ?: return@forEach
                        if (job.status == DownloadJobStatus.Pending.wire && source in blockedSources &&
                            (job.nextRetryAt == null || job.nextRetryAt!! <= now)
                        ) {
                            job.nextRetryAt = now + DownloadScheduler.BLOCKED_SOURCE_RECHECK_MILLIS
                            redeferredBlockedJobs = true
                        }
                    }
                    if (redeferredBlockedJobs) storage.saveQueue(queue)
                    pending =
                        DownloadScheduler.selectEligibleJobs(
                            jobs = queue,
                            now = now,
                            maxParallelSources = settings.maxParallelSources ?: 2,
                            activeCounts = activeCounts,
                            nextAllowedAt = emptyMap(),
                            lastScheduledSource = lastScheduledSource,
                            providerNameForJob = ::sourceIdForJob,
                            blockedSources = blockedSources,
                        )
                    if (pending.isNotEmpty()) {
                        pending.forEach { it.status = DownloadJobStatus.Downloading.wire }
                        storage.saveQueue(queue)
                    }
                }
                if (pending.isNotEmpty()) {
                    publishQueueChanged()
                    pending.forEach { job ->
                        val sourceId = sourceIdForJob(job) ?: return@forEach
                        val child =
                            launch(start = CoroutineStart.LAZY) {
                                try {
                                    processJob(job)
                                } finally {
                                    workerCompletions.trySend(job.id)
                                }
                            }
                        activeWorkers[job.id] = ActiveWorker(sourceId)
                        lastScheduledSource = sourceId
                        child.start()
                    }
                }
                emitProgress(null, queue)
                if (pending.isNotEmpty()) continue

                val sleepUntil =
                    DownloadScheduler.nextWakeUpAt(
                        jobs = queue,
                        now = nowMillis(),
                        nextAllowedAt = emptyMap(),
                        providerNameForJob = ::sourceIdForJob,
                    )
                if (activeWorkers.isEmpty()) {
                    if (sleepUntil == null) break
                    sleepUntilWakeOrTimeout(sleepUntil)
                } else {
                    awaitWorkerCompletionOrWake(workerCompletions, sleepUntil)?.let(activeWorkers::remove)
                }
            }
        }

    private suspend fun awaitWorkerCompletionOrWake(
        workerCompletions: ReceiveChannel<String>,
        sleepUntil: Long?,
    ): String? {
        suspend fun awaitEvent(): String? =
            select {
                workerCompletions.onReceive { it }
                wakeSignals.onReceive { null }
            }
        if (sleepUntil == null) return awaitEvent()
        val waitMillis = (sleepUntil - nowMillis()).coerceAtLeast(MINIMUM_WAIT_MILLIS)
        return withTimeoutOrNull(waitMillis) { awaitEvent() }
    }

    private suspend fun sleepUntilWakeOrTimeout(sleepUntil: Long) {
        val sleepMillis = (sleepUntil - nowMillis()).coerceAtLeast(MINIMUM_WAIT_MILLIS)
        withTimeoutOrNull(sleepMillis) { wakeSignals.receive() }
    }

    private fun sourceIdForJob(job: DownloadJob): String? = SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id

    private companion object {
        const val MINIMUM_WAIT_MILLIS = 200L
    }
}
