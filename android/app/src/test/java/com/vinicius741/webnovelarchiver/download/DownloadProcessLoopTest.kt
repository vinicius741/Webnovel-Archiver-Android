package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real download scheduling loop on virtual time. A worker "downloads" for
 * [WORK_MILLIS] and then commits its row as completed, the way the engine does.
 */
class DownloadProcessLoopTest {
    private val royalRoad = "royal_road"
    private val scribbleHub = "scribble_hub"

    private class Harness(
        val store: InMemoryDownloadQueueStore,
        private val scope: TestScope,
        private val isSourceBlocked: (String) -> Boolean = { false },
    ) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val starts = mutableListOf<Pair<String, Long>>()
        private val running = mutableMapOf<String, Int>()
        var maxRunningPerSource = 0
        var maxRunning = 0

        suspend fun run() {
            DownloadProcessLoop(
                storage = store,
                wakeSignals = wake,
                cleanupUnsupportedSourceJobs = {},
                processJob = ::work,
                publishQueueChanged = {},
                emitProgress = { _, _ -> },
                isSourceBlocked = isSourceBlocked,
                nowMillis = { scope.testScheduler.currentTime },
            ).run()
        }

        private suspend fun work(job: DownloadJob) {
            val source = job.sourceId!!
            starts += job.id to scope.testScheduler.currentTime
            running[source] = (running[source] ?: 0) + 1
            maxRunningPerSource = maxOf(maxRunningPerSource, running.getValue(source))
            maxRunning = maxOf(maxRunning, running.values.sum())
            delay(WORK_MILLIS)
            running[source] = running.getValue(source) - 1
            store.mutateQueueInPlace { rows ->
                rows.map { row -> if (row.id == job.id) DownloadJobTransitions.completed(row) ?: row else row }
            }
        }
    }

    @Test
    fun sourcesDownloadInParallelWhileEachSourceStaysInOrderOneAtATime() =
        runTest {
            val harness =
                Harness(
                    InMemoryDownloadQueueStore(
                        downloadJob("rr-1", royalRoad),
                        downloadJob("rr-2", royalRoad),
                        downloadJob("sh-1", scribbleHub),
                    ),
                    this,
                )

            harness.run()

            assertEquals(1, harness.maxRunningPerSource)
            assertEquals(2, harness.maxRunning)
            assertEquals(listOf("rr-1", "rr-2"), harness.starts.map { it.first }.filter { it.startsWith("rr") })
            // Two lanes: Scribble Hub overlaps Royal Road's first chapter instead of queueing behind it.
            assertEquals(2 * WORK_MILLIS, testScheduler.currentTime)
            assertAllCompleted(harness.store, "rr-1", "rr-2", "sh-1")
        }

    @Test
    fun parallelSourceSettingOfOneRunsSourcesOneAfterAnother() =
        runTest {
            val harness =
                Harness(
                    InMemoryDownloadQueueStore(
                        downloadJob("rr-1", royalRoad),
                        downloadJob("sh-1", scribbleHub),
                        maxParallelSources = 1,
                    ),
                    this,
                )

            harness.run()

            assertEquals(1, harness.maxRunning)
            assertEquals(2 * WORK_MILLIS, testScheduler.currentTime)
            assertAllCompleted(harness.store, "rr-1", "sh-1")
        }

    @Test
    fun aJobWaitingForItsRetryIsRunWhenTheDeadlinePassesInsteadOfBeingStranded() =
        runTest {
            val harness =
                Harness(InMemoryDownloadQueueStore(downloadJob("rr-1", royalRoad, retryCount = 1, nextRetryAt = 5_000L)), this)

            harness.run()

            assertEquals(listOf("rr-1" to 5_000L), harness.starts)
            assertAllCompleted(harness.store, "rr-1")
        }

    @Test
    fun workEnqueuedWhileAnotherSourceDownloadsStartsOnTheWakeSignal() =
        runTest {
            val harness = Harness(InMemoryDownloadQueueStore(downloadJob("sh-1", scribbleHub)), this)
            launch {
                delay(WORK_MILLIS / 4)
                harness.store.add(downloadJob("rr-1", royalRoad))
                harness.wake.send(Unit)
            }

            harness.run()

            // Started on the wake, not after Scribble Hub's in-flight chapter finished.
            assertEquals(WORK_MILLIS / 4, harness.starts.single { it.first == "rr-1" }.second)
            assertAllCompleted(harness.store, "sh-1", "rr-1")
        }

    @Test
    fun aBlockedSourceKeepsItsJobsPendingAndTheLoopAliveUntilVerificationPasses() =
        runTest {
            var blocked = true
            val harness =
                Harness(
                    InMemoryDownloadQueueStore(downloadJob("rr-1", royalRoad), downloadJob("sh-1", scribbleHub)),
                    this,
                    isSourceBlocked = { it == royalRoad && blocked },
                )
            val unblockAt = 90_000L
            launch {
                delay(unblockAt)
                val held = harness.store.job("rr-1")
                // Held, not failed against the open circuit, and deferred so the loop has a wake-up.
                assertEquals(DownloadJobStatus.Pending.wire, held.status)
                assertEquals(0, held.retryCount)
                assertTrue(held.nextRetryAt!! > unblockAt)
                blocked = false
            }

            harness.run()

            assertEquals(0L, harness.starts.single { it.first == "sh-1" }.second)
            assertTrue(harness.starts.single { it.first == "rr-1" }.second >= unblockAt)
            assertAllCompleted(harness.store, "rr-1", "sh-1")
            assertEquals(0, harness.store.job("rr-1").retryCount)
        }

    @Test
    fun pausedAndFailedRowsAreNeverStartedAndDoNotKeepTheLoopRunning() =
        runTest {
            val harness =
                Harness(
                    InMemoryDownloadQueueStore(
                        downloadJob("paused", royalRoad, status = DownloadJobStatus.Paused.wire),
                        downloadJob("failed", royalRoad, status = DownloadJobStatus.Failed.wire),
                        downloadJob("next", scribbleHub),
                    ),
                    this,
                )

            harness.run()

            assertEquals(listOf("next"), harness.starts.map { it.first })
            assertEquals(DownloadJobStatus.Paused.wire, harness.store.job("paused").status)
            assertEquals(DownloadJobStatus.Failed.wire, harness.store.job("failed").status)
            assertEquals(WORK_MILLIS, testScheduler.currentTime)
        }

    private fun assertAllCompleted(
        store: InMemoryDownloadQueueStore,
        vararg ids: String,
    ) {
        ids.forEach { id -> assertEquals("status of $id", DownloadJobStatus.Completed.wire, store.job(id).status) }
    }

    private companion object {
        const val WORK_MILLIS = 1_000L
    }
}
