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
internal class DownloadProcessLoop(
    private val storage: AppStorage,
    private val wakeSignals: ReceiveChannel<Unit>,
    private val cleanupUnsupportedSourceJobs: () -> Unit,
    private val processJob: suspend (DownloadJob) -> Unit,
    private val publishQueueChanged: () -> Unit,
    private val emitProgress: (DownloadJob?, List<DownloadJob>) -> Unit,
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
                    val activeCounts = activeWorkers.values.groupingBy(ActiveWorker::sourceId).eachCount()
                    pending =
                        DownloadScheduler.selectEligibleJobs(
                            jobs = queue,
                            now = nowMillis(),
                            maxParallelSources = settings.maxParallelSources ?: 2,
                            activeCounts = activeCounts,
                            nextAllowedAt = emptyMap(),
                            lastScheduledSource = lastScheduledSource,
                            providerNameForJob = ::sourceIdForJob,
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
