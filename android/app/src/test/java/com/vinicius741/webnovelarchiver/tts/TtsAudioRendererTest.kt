package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsAudioRendererTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class Recorder : TtsAudioRenderer.Callbacks {
        val rendered = mutableListOf<Pair<String, Long>>()
        val failed = mutableListOf<String>()
        var finished = 0

        override fun onRendered(
            request: TtsRenderRequest,
            durationMs: Long,
        ) {
            rendered += request.text to durationMs
        }

        override fun onRenderFailed(request: TtsRenderRequest) {
            failed += request.text
        }

        override fun onPlanFinished() {
            finished++
        }
    }

    private fun wavWithSpeech(frames: Int): ByteArray {
        val buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        buffer
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(24_000)
            .putInt(48_000)
            .putShort(2)
            .putShort(16)
        buffer.put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { buffer.putShort(10_000) }
        return buffer.array()
    }

    private fun requests(
        cache: TtsAudioCache,
        vararg texts: String,
    ) = texts.map { TtsRenderRequest(cache.keyFor("voice", it), it) }

    @Test
    fun rendersInPlanOrderAndReusesTheCache() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val spoken = mutableListOf<String>()
            val synthesizer =
                TtsSpeechSynthesizer { text, output: File ->
                    spoken += text
                    output.writeBytes(wavWithSpeech(24_000))
                    true
                }
            val recorder = Recorder()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val renderer = TtsAudioRenderer(cache, synthesizer, scope, recorder)

            renderer.setPlan(requests(cache, "one", "two"))
            testScheduler.advanceUntilIdle()
            renderer.setPlan(requests(cache, "two", "three"))
            testScheduler.advanceUntilIdle()
            renderer.stop()

            assertEquals(listOf("one", "two", "three"), spoken)
            assertEquals(listOf("one", "two", "two", "three"), recorder.rendered.map { it.first })
            assertTrue(recorder.rendered.all { it.second > 900L })
            assertEquals(2, recorder.finished)
            assertTrue(cache.read(cache.keyFor("voice", "one"))!!.frames > 0)
        }

    @Test
    fun aFailedRenderIsReportedOnceAndDropsTheRestOfThePlan() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val renderer = TtsAudioRenderer(cache, { _, _ -> false }, scope, recorder)

            renderer.setPlan(requests(cache, "one", "two"))
            testScheduler.advanceUntilIdle()
            renderer.stop()

            assertEquals(listOf("one"), recorder.failed)
            assertEquals(0, recorder.finished)
        }

    @Test
    fun aSingleFailureIsRetried() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            var attempts = 0
            val synthesizer =
                TtsSpeechSynthesizer { _, output: File ->
                    attempts++
                    output.writeBytes(wavWithSpeech(24_000))
                    attempts > 1
                }
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val renderer = TtsAudioRenderer(cache, synthesizer, scope, recorder)

            renderer.setPlan(requests(cache, "one"))
            testScheduler.advanceUntilIdle()
            renderer.stop()

            assertEquals(emptyList<String>(), recorder.failed)
            assertEquals(listOf("one"), recorder.rendered.map { it.first })
        }

    @Test
    fun anEngineThatOnlyProducesNothingCountsAsFailed() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val renderer = TtsAudioRenderer(cache, { _, _ -> true }, scope, recorder)

            renderer.setPlan(requests(cache, "a", "b", "c", "d", "e", "f"))
            testScheduler.advanceUntilIdle()
            renderer.stop()

            assertTrue(recorder.failed.isNotEmpty())
        }

    @Test
    fun playbackRequestsPreemptRenderAheadWithoutDuplicatingInFlightWork() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            val spoken = mutableListOf<String>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseMissing = CompletableDeferred<Unit>()
            val renderer =
                TtsAudioRenderer(
                    cache,
                    { text, output ->
                        spoken += text
                        if (text == "ahead one") releaseFirst.await()
                        if (text == "missing") releaseMissing.await()
                        output.writeBytes(wavWithSpeech(24_000))
                        true
                    },
                    backgroundScope,
                    recorder,
                )
            renderer.setPlan(requests(cache, "ahead one", "ahead two"))
            runCurrent()
            val missing = requests(cache, "missing").single()
            repeat(5) { renderer.request(missing) }
            releaseFirst.complete(Unit)
            runCurrent()
            repeat(5) { renderer.request(missing) }
            releaseMissing.complete(Unit)
            runCurrent()
            renderer.stop()

            assertEquals(listOf("ahead one", "missing", "ahead two"), spoken)
            assertTrue(recorder.failed.isEmpty())
        }

    @Test
    fun thrownSynthesisFailureReachesFallbackInsteadOfKillingTheWorkerSilently() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            val renderer =
                TtsAudioRenderer(cache, { _, _ -> throw IOException("cannot write audio") }, backgroundScope, recorder)
            renderer.setPlan(requests(cache, "one", "two"))
            runCurrent()
            renderer.stop()

            assertEquals(listOf("one"), recorder.failed)
            assertEquals(0, recorder.finished)
        }

    @Test
    fun stoppingASuspendedRenderDoesNotReportAFailure() =
        runTest {
            val cache = TtsAudioCache(folder.newFolder())
            val recorder = Recorder()
            val pending = CompletableDeferred<Boolean>()
            val renderer = TtsAudioRenderer(cache, { _, _ -> pending.await() }, backgroundScope, recorder)
            renderer.setPlan(requests(cache, "one"))
            runCurrent()
            renderer.stop()
            runCurrent()

            assertTrue(recorder.failed.isEmpty())
            assertTrue(recorder.rendered.isEmpty())
            assertEquals(0, recorder.finished)
        }

    @Test
    fun cacheEvictsLeastRecentlyUsedOverItsLimit() {
        val dir = folder.newFolder()
        val cache = TtsAudioCache(dir, maxBytes = 1_000L)
        val clip = TtsPcmClip(24_000, ByteArray(400))
        repeat(70) { index -> cache.write(cache.keyFor("voice", "line $index"), clip) }
        assertTrue(dir.listFiles().orEmpty().sumOf { it.length() } < 70 * 404L)
    }
}
