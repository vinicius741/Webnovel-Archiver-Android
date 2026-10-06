package com.vinicius741.webnovelarchiver.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private data class TestJob(
    override val storyId: String,
    override val message: String,
    val attachment: String? = null,
) : AiJobModel

private sealed interface TestEvent : AiJobEvent {
    data class Done(
        override val storyId: String,
    ) : TestEvent

    data class Failed(
        override val storyId: String,
        val attachment: String?,
    ) : TestEvent
}

private fun coordinator(
    scope: kotlinx.coroutines.CoroutineScope,
    queueEnabled: Boolean = false,
): AiJobCoordinator<TestJob, TestEvent> =
    AiJobCoordinator(
        scope = scope,
        keyOf = { it.storyId },
        withMessage = { state, message -> state.copy(message = message) },
        failureEvent = { state, error -> TestEvent.Failed(state.storyId, state.attachment) },
        queueEnabled = queueEnabled,
        queuedStartMessage = "Starting...",
    )

class AiJobCoordinatorTest {
    @Test
    fun withoutQueueOnlyOneJobRunsAndSecondSubmitIsRejected() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val coordinator = coordinator(backgroundScope)
            val accepted =
                coordinator.submit(TestJob("a", "working")) { _ ->
                    gate.await()
                    TestEvent.Done("a")
                }
            assertTrue(accepted)
            assertFalse(coordinator.submit(TestJob("b", "working")) { TestEvent.Done("b") })
            assertTrue(coordinator.isBusy())
            assertEquals("a", coordinator.jobFor("a")?.storyId)
            gate.complete(Unit)
        }

    @Test
    fun queuedJobsDrainSequentiallyAndEmitEventsAfterFinishing() =
        runTest {
            val gates = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred<Unit>())
            val coordinator = coordinator(backgroundScope, queueEnabled = true)
            val events = CopyOnWriteArrayList<TestEvent>()
            backgroundScope.async { coordinator.events.collect { events.add(it) } }

            assertTrue(
                coordinator.submit(TestJob("a", "queued")) {
                    gates.getValue("a").await()
                    TestEvent.Done("a")
                },
            )
            assertTrue(
                coordinator.submit(TestJob("b", "queued")) {
                    gates.getValue("b").await()
                    TestEvent.Done("b")
                },
            )

            // Exactly one job registered; b still visible in the queue under its queued message.
            assertEquals(setOf("a"), coordinator.jobs.value.keys)
            assertEquals(listOf("queued"), coordinator.queue.value.map { it.message })
            assertTrue(events.isEmpty())

            gates.getValue("a").complete(Unit)
            withTimeout(5_000) {
                while ("b" !in coordinator.jobs.value.keys) kotlinx.coroutines.delay(10)
            }
            // The queued entry starts with the start message swap.
            assertEquals("Starting...", coordinator.jobFor("b")?.message)
            assertTrue(coordinator.queue.value.isEmpty())

            gates.getValue("b").complete(Unit)
            withTimeout(5_000) {
                while (events.size < 2) kotlinx.coroutines.delay(10)
            }
            assertEquals(listOf<TestEvent>(TestEvent.Done("a"), TestEvent.Done("b")), events.toList())
            assertFalse(coordinator.isBusy())
        }

    @Test
    fun nullResultDiscardsSilentlyAndReleasesTheSlot() =
        runTest {
            val coordinator = coordinator(backgroundScope, queueEnabled = true)
            val events = CopyOnWriteArrayList<TestEvent>()
            backgroundScope.async { coordinator.events.collect { events.add(it) } }

            assertTrue(coordinator.submit(TestJob("a", "working")) { null })
            withTimeout(5_000) {
                while (coordinator.isBusy()) kotlinx.coroutines.delay(10)
            }
            assertTrue(events.isEmpty())
            assertNull(coordinator.jobFor("a"))
        }

    @Test
    fun failureCarriesTheLatestMidRunState() =
        runTest {
            val coordinator = coordinator(backgroundScope)
            val events = CopyOnWriteArrayList<TestEvent>()
            backgroundScope.async { coordinator.events.collect { events.add(it) } }

            coordinator.submit(TestJob("a", "working")) { update ->
                update { it.copy(attachment = "persisted-prompt") }
                error("boom")
            }
            withTimeout(5_000) {
                while (events.isEmpty()) kotlinx.coroutines.delay(10)
            }
            assertEquals(TestEvent.Failed("a", "persisted-prompt"), events.single())
            assertFalse(coordinator.isBusy())
        }

    @Test
    fun cancelAllDropsQueueAndCancelsTheActiveJob() =
        runTest {
            // Cancellation must actually resume the suspended job, which the virtual-time
            // background dispatcher does not deliver — run this coordinator on a real one.
            val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
            val coordinator = coordinator(scope, queueEnabled = true)
            val started = CopyOnWriteArrayList<String>()
            val cancelled = CopyOnWriteArrayList<String>()
            assertTrue(
                coordinator.submit(TestJob("a", "running")) {
                    started.add("a")
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelled.add("a")
                        throw e
                    }
                },
            )
            assertTrue(coordinator.submit(TestJob("b", "queued")) { TestEvent.Done("b") })
            assertEquals(1, coordinator.queue.value.size)
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000) {
                    while (started.isEmpty()) kotlinx.coroutines.delay(10)
                }
            }

            coordinator.cancelAll("test")
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000) {
                    while (cancelled.isEmpty()) kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(coordinator.queue.value.isEmpty())
            assertFalse(coordinator.isBusy())
            scope.cancel()
        }

    @Test
    fun cancelQueuedForStoryRemovesOnlyThatStory() =
        runTest {
            val coordinator = coordinator(backgroundScope, queueEnabled = true)
            val gate = CompletableDeferred<Unit>()
            coordinator.submit(TestJob("a", "running")) {
                gate.await()
                TestEvent.Done("a")
            }
            coordinator.submit(TestJob("s1", "queued")) { TestEvent.Done("s1") }
            coordinator.submit(TestJob("s2", "queued")) { TestEvent.Done("s2") }
            coordinator.submit(TestJob("other", "queued")) { TestEvent.Done("other") }

            val removed = coordinator.cancelQueuedForStory("s1")
            assertEquals(1, removed)
            assertEquals(listOf("s2", "other"), coordinator.queue.value.map { it.storyId })
            gate.complete(Unit)
        }
}
