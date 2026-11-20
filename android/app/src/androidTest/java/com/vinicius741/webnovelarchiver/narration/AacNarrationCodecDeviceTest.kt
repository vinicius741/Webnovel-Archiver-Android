package com.vinicius741.webnovelarchiver.narration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Android's real AAC codecs, which unit tests cannot run: exact length, alignment, level and size. */
@RunWith(AndroidJUnit4::class)
class AacNarrationCodecDeviceTest {
    private val codec = AacNarrationCodec()

    @Test
    fun aSentenceComesBackAlignedAtItsLevelAndFiveTimesSmaller() {
        for (rate in listOf(24_000, 22_050)) {
            val original = speechLike(rate, seconds = 2.3)
            val encoded = codec.encode(TtsPcmClip(rate, original))
            assertNotNull("no AAC encoder at $rate Hz", encoded)
            encoded!!
            val stored = NarrationClipFormat.write(encoded)
            assertTrue("rate $rate: ${stored.size} of ${original.size} bytes", stored.size * 4 < original.size)
            assertTrue("rate $rate: delay ${encoded.delayFrames}", encoded.delayFrames in 1..4_096)
            val decoded = codec.decode(NarrationClipFormat.read(stored)!!)!!
            assertEquals(original.size, decoded.size)
            val x = samples(original)
            val y = samples(decoded)
            // A misaligned clip (even by one sample) loses the higher harmonics' phase and scores far lower.
            assertTrue("rate $rate: SNR ${snr(x, y)} dB", snr(x, y) > 15.0)
            val level = rms(y) / rms(x)
            assertTrue("rate $rate: level $level", level in 0.9..1.1)
        }
    }

    @Test
    fun anEmptyClipStaysEmpty() {
        val encoded = codec.encode(TtsPcmClip(24_000, ByteArray(0)))!!
        assertEquals(0, encoded.frames)
        assertArrayEquals(ByteArray(0), codec.decode(NarrationClipFormat.read(NarrationClipFormat.write(encoded))!!))
    }

    @Test
    fun theStoreWritesCompressedClipsAndPlaysThemBack() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "aac-device-test",
            ).apply { deleteRecursively() }
        try {
            val store = NarrationStore(root, codec).apply { load() }
            val recipe =
                NarrationRecipe(storyId = "s", chapterId = "c", sentences = listOf("One."), voice = NarrationVoice("e", "v", 1f, "V"))
            val dir = store.createVersion(recipe)
            val pcm = speechLike(24_000, seconds = 1.0)
            store.writeClip(store.clipFile(dir, 0), TtsPcmClip(24_000, pcm))
            val index = store.indexFor(dir, recipe, 0L)!!
            assertEquals(NarrationStore.CODEC_AAC, index.codec)
            assertEquals(listOf(1_000L), index.sentenceDurationsMs)
            assertEquals(pcm.size, store.readClip(store.clipFile(dir, 0))!!.pcm.size)
        } finally {
            root.deleteRecursively()
        }
    }

    /** A voiced sound: a gliding pitch with harmonics up to about 5 kHz, in syllable-like bursts. */
    private fun speechLike(
        rate: Int,
        seconds: Double,
    ): ByteArray {
        val frames = (rate * seconds).toInt()
        val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (n in 0 until frames) {
            val t = n.toDouble() / rate
            val f0 = 150 + 50 * sin(2 * PI * 0.7 * t)
            phase += 2 * PI * f0 / rate
            var sample = 0.0
            for (h in 1..30) if (h * f0 < 5_000) sample += sin(h * phase) / h
            val envelope = 0.5 + 0.5 * sin(2 * PI * 4 * t)
            out.putShort((sample * envelope * 5_000).toInt().coerceIn(-32_768, 32_767).toShort())
        }
        return out.array()
    }

    private fun samples(pcm: ByteArray): DoubleArray {
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer
            .wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return DoubleArray(shorts.size) { shorts[it].toDouble() }
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    private fun snr(
        x: DoubleArray,
        y: DoubleArray,
    ): Double {
        val noise = x.indices.sumOf { (x[it] - y[it]) * (x[it] - y[it]) }
        return 10 * log10(x.sumOf { it * it } / noise)
    }
}
