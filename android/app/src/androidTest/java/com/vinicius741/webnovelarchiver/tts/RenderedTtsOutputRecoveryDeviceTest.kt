package com.vinicius741.webnovelarchiver.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RenderedTtsOutputRecoveryDeviceTest {
    @Test
    fun missingCachedSentenceRecoversAfterPauseAndResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "tts-recovery").toFile()
        val cache = TtsAudioCache(dir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rendered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val attempts = ConcurrentHashMap<String, Int>()
        val output =
            RenderedTtsOutput(
                cache = cache,
                synthesizer = { text, file ->
                    attempts.merge(text, 1, Int::plus)
                    file.writeBytes(speechWav())
                    true
                },
                scope = scope,
                listener =
                    object : TtsOutput.Listener {
                        override fun onItemStarted(item: Int) = Unit

                        override fun onEnded() = ended.countDown()

                        override fun onUnavailable() {
                            failure.set("rendered output became unavailable")
                            ended.countDown()
                        }

                        override fun onError(error: TtsPlaybackError) {
                            failure.set(error.toString())
                            ended.countDown()
                        }

                        override fun onRenderPlanFinished() = rendered.countDown()
                    },
                dispatch = { it() },
            )
        try {
            val timeline =
                TtsTimeline(
                    listOf(
                        TtsTimelineItem.Speech("first sentence", 0),
                        TtsTimelineItem.Silence(1_000L, 0),
                        TtsTimelineItem.Speech("missing sentence", 1),
                    ),
                )
            output.load(TtsOutputLoad(timeline, 0, 0L, 1f, false, "voice"))
            assertTrue("render plan never finished", rendered.await(5, TimeUnit.SECONDS))
            // The render plan has finished and moved on, then Android reclaims a needed cache file.
            val missing = cache.keyFor("voice", "missing sentence")
            assertTrue(dir.resolve("$missing.pcm").delete())
            output.prepareAhead(listOf("next chapter"))
            output.play()
            SystemClock.sleep(2_500L)
            output.pause()
            SystemClock.sleep(300L)
            output.play()
            assertTrue("playback stayed stuck after pause and resume", ended.await(10, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertEquals(2, attempts["missing sentence"])
        } finally {
            output.release()
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private fun speechWav(): ByteArray {
        val frames = 24_000
        val buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        buffer
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
        buffer
            .putInt(24_000)
            .putInt(48_000)
            .putShort(2)
            .putShort(16)
        buffer.put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { buffer.putShort(if (it % 48 < 24) 200 else -200) }
        return buffer.array()
    }
}
