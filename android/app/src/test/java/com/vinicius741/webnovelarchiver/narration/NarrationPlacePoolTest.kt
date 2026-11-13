package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NarrationPlacePoolTest {
    private val voice = NarrationVoice("builtin:kokoro-en-v0_19", null, 1f, "Bella")
    private val output = File("unused")

    private class FakePlace(
        override val id: String,
        override val slots: Int = 2,
    ) : NarrationPlace {
        override val name: String = id.replaceFirstChar { it.uppercase() }
        var reachable = true

        /** Every sentence answers `503` with this while set. */
        var busy: NarrationServerBusy? = null
        var speakMs = 1_000L
        val spoken = mutableListOf<String>()
        var running = 0
        var maxRunning = 0
        var connects = 0
        var closed = false

        override suspend fun connect(checkMemory: Boolean) {
            connects++
            if (!reachable) throw NarrationServerProblem("Can't reach $name", transient = true)
        }

        override suspend fun synthesize(text: String): TtsPcmClip? {
            busy?.let { throw it }
            if (!reachable) throw NarrationServerProblem("Lost the connection to $name", transient = true)
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                delay(speakMs)
            } finally {
                running--
            }
            if (!reachable) throw NarrationServerProblem("Lost the connection to $name", transient = true)
            spoken += text
            return TtsPcmClip(24_000, ByteArray(4))
        }

        override fun close() {
            closed = true
        }
    }

    private class FakePhone(
        override val voice: NarrationVoice,
    ) : NarrationSynthesizer {
        override val voiceKey = "phone"
        val spoken = mutableListOf<String>()
        var closed = false

        override suspend fun synthesize(
            text: String,
            output: File,
        ): TtsPcmClip {
            delay(4_000)
            spoken += text
            return TtsPcmClip(24_000, ByteArray(2))
        }

        override fun close() {
            closed = true
        }
    }

    private suspend fun TestScope.pool(
        vararg places: FakePlace,
        phone: (suspend () -> NarrationSynthesizer)? = null,
        scope: CoroutineScope = backgroundScope,
    ): NarrationPlacePool =
        NarrationPlacePool.open(
            voice = voice,
            voiceKey = "kokoro|speaker=0",
            location = places.joinToString(",") { it.id },
            places = places.toList(),
            phone = phone,
            scope = scope,
            now = { testScheduler.currentTime },
        )

    private suspend fun TestScope.speak(
        pool: NarrationPlacePool,
        count: Int,
        from: Int = 0,
    ): List<TtsPcmClip?> = (from until from + count).map { index -> async { pool.synthesize("Sentence $index.", output) } }.awaitAll()

    private fun busy(stopped: Boolean) = NarrationServerBusy("Mac is low on memory and has stopped generating", 20_000L, stopped)

    @Test
    fun computersShareTheSentencesAndTheirSlots() =
        runTest {
            val mac = FakePlace("mac")
            val bazzite = FakePlace("bazzite")
            val pool = pool(mac, bazzite)
            assertEquals(4, pool.parallelism)
            assertEquals("Mac + Bazzite", pool.locationName)
            assertEquals("mac+bazzite", pool.speedPlace)

            val clips = speak(pool, 8)

            assertEquals(8, clips.filterNotNull().size)
            assertEquals(4, mac.spoken.size)
            assertEquals(4, bazzite.spoken.size)
            assertEquals(2, mac.maxRunning)
            assertEquals(2, bazzite.maxRunning)
            // Four at a time: eight one-second sentences take two seconds.
            assertEquals(2_000L, testScheduler.currentTime)
            pool.close()
            assertTrue(mac.closed && bazzite.closed)
        }

    @Test
    fun aComputerThatDropsHandsItsSentencesToTheOthers() =
        runTest {
            val mac = FakePlace("mac")
            val bazzite = FakePlace("bazzite")
            val pool = pool(mac, bazzite)
            val clips = async { speak(pool, 6) }
            advanceTimeBy(500)
            mac.reachable = false

            assertEquals(6, clips.await().filterNotNull().size)
            assertTrue(mac.spoken.isEmpty())
            assertEquals(6, bazzite.spoken.size)
            assertEquals("Bazzite", pool.locationName)
            assertEquals(2, pool.parallelism)
            assertNull(pool.waitingFor.value)
        }

    @Test
    fun aComputerThatComesBackRejoins() =
        runTest {
            val mac = FakePlace("mac").apply { reachable = false }
            val bazzite = FakePlace("bazzite")
            val pool = pool(mac, bazzite)
            assertEquals("Bazzite", pool.locationName)
            assertEquals(2, pool.parallelism)

            mac.reachable = true
            advanceTimeBy(NarrationPlacePool.PROBE_FIRST_MS + 1)
            runCurrent()

            assertEquals("Mac + Bazzite", pool.locationName)
            assertEquals(4, pool.parallelism)
            speak(pool, 4)
            assertEquals(2, mac.spoken.size)
        }

    @Test
    fun everyComputerOutStopsGenerationWithOneMessage() =
        runTest {
            val mac = FakePlace("mac").apply { reachable = false }
            val bazzite = FakePlace("bazzite").apply { reachable = false }
            try {
                pool(mac, bazzite)
                fail("Expected a server problem")
            } catch (problem: NarrationServerProblem) {
                assertEquals("Can't reach Mac or Bazzite. Check that they are on and the server is running", problem.message)
            }
            assertTrue(mac.closed && bazzite.closed)
        }

    @Test
    fun theLastComputerDroppingMidChapterStopsGeneration() =
        runTest {
            val mac = FakePlace("mac")
            val pool = pool(mac)
            speak(pool, 2)
            mac.reachable = false
            try {
                pool.synthesize("Next.", output)
                fail("Expected a server problem")
            } catch (problem: NarrationServerProblem) {
                assertEquals("Lost the connection to Mac", problem.message)
            }
        }

    @Test
    fun thisPhoneStandsInWhileEveryComputerIsOutAndStepsBackAfter() =
        runTest {
            val mac = FakePlace("mac").apply { reachable = false }
            var phone: FakePhone? = null
            val pool = pool(mac, phone = { FakePhone(voice).also { phone = it } })

            pool.synthesize("On the phone.", output)
            assertEquals(listOf("On the phone."), phone?.spoken)
            assertEquals(NarrationPlacePool.PHONE_NAME, pool.locationName)
            assertEquals(1, pool.parallelism)

            mac.reachable = true
            advanceTimeBy(NarrationPlacePool.PROBE_FIRST_MS + 1)
            runCurrent()
            pool.synthesize("On the Mac.", output)

            assertEquals(listOf("On the Mac."), mac.spoken)
            assertTrue(phone?.closed == true)
            assertEquals("Mac", pool.locationName)
        }

    @Test
    fun aComputerStoppedForMemoryIsWaitedOutAndShown() =
        runTest {
            val mac = FakePlace("mac").apply { busy = busy(stopped = true) }
            val pool = pool(mac)
            val clip = async { pool.synthesize("Waits.", output) }
            runCurrent()
            assertEquals("Mac is low on memory and has stopped generating", pool.waitingFor.value)

            advanceTimeBy(60_000)
            assertFalse(clip.isCompleted)
            mac.busy = null
            advanceTimeBy(30_000)

            assertEquals(listOf("Waits."), mac.spoken)
            assertTrue(clip.isCompleted)
            assertNull(pool.waitingFor.value)
        }

    @Test
    fun aComputerStoppedTooLongPausesGeneration() =
        runTest {
            val mac = FakePlace("mac").apply { busy = busy(stopped = true) }
            val pool = pool(mac)
            try {
                pool.synthesize("Never.", output)
                fail("Expected a server problem")
            } catch (problem: NarrationServerProblem) {
                assertEquals("Mac is low on memory and has stopped generating. Resume when it has memory again", problem.message)
            }
            assertTrue(testScheduler.currentTime >= NarrationPlacePool.MAX_STOPPED_WAIT_MS)
        }

    @Test
    fun whileOneComputerHoldsBackTheOtherKeepsGenerating() =
        runTest {
            val mac = FakePlace("mac").apply { busy = busy(stopped = true) }
            val bazzite = FakePlace("bazzite")
            val pool = pool(mac, bazzite)

            speak(pool, 6)

            assertEquals(6, bazzite.spoken.size)
            assertNull(pool.waitingFor.value)
            // Six one-second sentences two at a time, plus nothing lost waiting on the Mac.
            assertEquals(3_000L, testScheduler.currentTime)
        }
}
