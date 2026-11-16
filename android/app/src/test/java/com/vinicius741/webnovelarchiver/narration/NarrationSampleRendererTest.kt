package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NarrationSampleRendererTest {
    private class FakePlace(
        override val name: String,
        private val slots: Int = 1,
        private val connectProblem: Exception? = null,
        private val answer: suspend (String) -> TtsPcmClip? = { text -> clip(text) },
    ) : NarrationSamplePlace {
        val spoken = mutableListOf<String>()
        var closed = false

        override suspend fun connect(): Int {
            connectProblem?.let { throw it }
            return slots
        }

        override suspend fun synthesize(text: String): TtsPcmClip? {
            delay(10)
            return answer(text)?.also { spoken += text }
        }

        override fun close() {
            closed = true
        }
    }

    private fun TestScope.renderer(
        vararg places: NarrationSamplePlace,
        maxBusyWaitMs: Long = NarrationSampleRenderer.MAX_BUSY_WAIT_MS,
    ) = NarrationSampleRenderer(places.toList(), maxBusyWaitMs = maxBusyWaitMs, now = { currentTime }, trim = { it })

    private fun List<TtsPcmClip>.texts() = map { it.pcm.toString(Charsets.UTF_8) }

    private suspend fun expectProblem(block: suspend () -> Unit): NarrationServerProblem {
        try {
            block()
        } catch (problem: NarrationServerProblem) {
            return problem
        }
        fail("expected a problem")
        error("unreachable")
    }

    @Test
    fun `clips come back in text order from every chosen computer, with progress that only grows`() =
        runTest {
            val a = FakePlace("A", slots = 2)
            val b = FakePlace("B", slots = 2)
            val texts = (1..8).map { "sentence $it" }
            val progress = mutableListOf<Int>()

            val clips = renderer(a, b).render(texts) { done, _ -> progress += done }

            assertEquals(texts, clips.texts())
            assertEquals((1..8).toList(), progress)
            assertTrue(a.spoken.isNotEmpty() && b.spoken.isNotEmpty())
            assertTrue(a.closed && b.closed)
        }

    @Test
    fun `clips are trimmed like a chapter's`() =
        runTest {
            val clips =
                NarrationSampleRenderer(listOf(FakePlace("A")), trim = { TtsPcmClip(it.sampleRate, it.pcm.copyOf(2)) })
                    .render(listOf("long sentence"))

            assertEquals("lo", clips.texts().single())
        }

    @Test
    fun `an empty sample asks no computer`() =
        runTest {
            val place = FakePlace("A")

            assertEquals(emptyList<TtsPcmClip>(), renderer(place).render(emptyList()))
            assertTrue(place.spoken.isEmpty())
        }

    @Test
    fun `a computer that cannot be reached leaves its sentences to the others`() =
        runTest {
            val down = FakePlace("Down", connectProblem = NarrationServerProblem("Can't reach Down", transient = true))
            val up = FakePlace("Up")
            val generatingOn = mutableListOf<List<String>>()

            val clips = renderer(down, up).render(listOf("one", "two")) { _, names -> generatingOn += names }

            assertEquals(listOf("one", "two"), clips.texts())
            assertEquals(listOf("Up"), generatingOn.last())
        }

    @Test
    fun `a computer that drops mid-sample hands its sentence back`() =
        runTest {
            var calls = 0
            val flaky =
                FakePlace("Flaky") { text ->
                    calls++
                    if (calls == 1) throw NarrationServerProblem("Lost Flaky", transient = true)
                    clip(text)
                }
            val steady = FakePlace("Steady")

            assertEquals(listOf("one", "two", "three"), renderer(flaky, steady).render(listOf("one", "two", "three")).texts())
        }

    @Test
    fun `an unexpected error takes out only that computer`() =
        runTest {
            val broken = FakePlace("Broken") { throw IllegalStateException("bad WAV") }
            val steady = FakePlace("Steady")

            assertEquals(listOf("one", "two"), renderer(broken, steady).render(listOf("one", "two")).texts())

            val alone = FakePlace("Alone") { throw IllegalStateException("bad WAV") }
            assertEquals("Alone could not generate the sample", expectProblem { renderer(alone).render(listOf("one")) }.message)
        }

    @Test
    fun `no usable computer fails with the reason`() =
        runTest {
            val wrong = FakePlace("Mac", connectProblem = NarrationServerRejected("Mac refused the token"))

            assertEquals("Mac refused the token", expectProblem { renderer(wrong).render(listOf("one")) }.message)
        }

    @Test
    fun `a sentence one computer cannot generate goes to another`() =
        runTest {
            val picky = FakePlace("Picky") { text -> if (text == "bad") null else clip(text) }
            val able = FakePlace("Able")

            assertEquals(listOf("good", "bad", "fine"), renderer(picky, able).render(listOf("good", "bad", "fine")).texts())
        }

    @Test
    fun `a sentence no computer can generate fails the sample after a retry on each`() =
        runTest {
            var attempts = 0
            val place =
                FakePlace("A") { text ->
                    if (text == "bad") {
                        attempts++
                        null
                    } else {
                        clip(text)
                    }
                }

            val problem = expectProblem { renderer(place).render(listOf("good", "bad")) }

            assertEquals("A could not generate a sentence of the sample", problem.message)
            assertEquals(2, attempts)
        }

    @Test
    fun `a busy computer is waited out, then given up on by the clock, not per slot`() =
        runTest {
            var busyLeft = 1
            val briefly =
                FakePlace("Busy") { text ->
                    if (busyLeft-- > 0) throw NarrationServerBusy("Busy is low on memory", retryAfterMs = 1_000, stopped = false)
                    clip(text)
                }
            assertEquals(1, renderer(briefly).render(listOf("one")).size)

            // Three slots refused at once wait 10 s together, not 30 s between them.
            var refusals = 0
            val recovering =
                FakePlace("Recovering", slots = 3) { text ->
                    if (currentTime < 15_000) {
                        refusals++
                        throw NarrationServerBusy("Recovering is low on memory", retryAfterMs = 10_000, stopped = false)
                    }
                    clip(text)
                }
            assertEquals(3, renderer(recovering, maxBusyWaitMs = 20_000).render(listOf("a", "b", "c")).size)
            assertTrue(refusals >= 3)

            val full =
                FakePlace("Full") { throw NarrationServerBusy("Full is low on memory", retryAfterMs = 10_000, stopped = false) }
            assertEquals("Full is low on memory", expectProblem { renderer(full, maxBusyWaitMs = 20_000).render(listOf("one")) }.message)
        }

    @Test
    fun `an idle computer takes over a sentence a stuck one is on`() =
        runTest {
            val stuck = FakePlace("Stuck") { awaitCancellation() }
            val quick = FakePlace("Quick")

            val clips = renderer(stuck, quick).render(listOf("one", "two", "three"))

            assertEquals(listOf("one", "two", "three"), clips.texts())
            assertTrue("finished long before any read timeout", currentTime < 5_000)
            assertTrue(stuck.closed)
        }

    @Test
    fun `cancelling closes every computer`() =
        runTest {
            val slow = FakePlace("Slow") { awaitCancellation() }
            var cancelled = false
            val job =
                launch {
                    try {
                        renderer(slow).render(listOf("one"))
                    } catch (expected: CancellationException) {
                        cancelled = true
                        throw expected
                    }
                }
            runCurrent()
            advanceTimeBy(1_000)
            assertFalse(slow.closed)

            job.cancel()
            job.join()

            assertTrue(cancelled)
            assertTrue(slow.closed)
        }

    @Test
    fun `no computer chosen fails at once`() =
        runTest {
            assertEquals("No computer is chosen to generate on", expectProblem { renderer().render(listOf("one")) }.message)
        }

    private companion object {
        fun clip(text: String) = TtsPcmClip(24_000, text.toByteArray(Charsets.UTF_8))
    }
}
