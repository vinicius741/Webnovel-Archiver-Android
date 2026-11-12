package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsWavWriterTest {
    @Test
    fun rawPcmMatchesTheWavPathIncludingClippingAndEmptyAudio() {
        listOf(floatArrayOf(-3f, -1f, -0.5f, 0f, 0.5f, 1f, 1.7f), FloatArray(0)).forEach { samples ->
            val wav = TtsPcmPlanning.parseWav(TtsWavWriter.mono16(samples, 24_000))!!
            val raw = TtsWavWriter.pcm16(samples, 24_000)
            assertEquals(wav.sampleRate, raw.sampleRate)
            assertArrayEquals(wav.pcm, raw.pcm)
        }
    }

    @Test
    fun `written audio parses back as the same mono 16-bit samples`() {
        val wav = TtsWavWriter.mono16(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f), 24_000)

        val clip = TtsPcmPlanning.parseWav(wav)!!

        assertEquals(24_000, clip.sampleRate)
        assertEquals(listOf(0, 16_384, -16_383, 32_767, -32_767), samples(clip.pcm))
    }

    @Test
    fun `out of range samples are clipped instead of wrapping`() {
        val clip = TtsPcmPlanning.parseWav(TtsWavWriter.mono16(floatArrayOf(1.7f, -3f), 24_000))!!

        assertEquals(listOf(32_767, -32_767), samples(clip.pcm))
    }

    @Test
    fun `no samples still makes a valid empty clip`() {
        val clip = TtsPcmPlanning.parseWav(TtsWavWriter.mono16(FloatArray(0), 24_000))

        assertEquals(0, clip?.pcm?.size ?: 0)
    }

    private fun samples(pcm: ByteArray): List<Int> =
        pcm.toList().chunked(2).map { (low, high) -> (high.toInt() shl 8) or (low.toInt() and 0xFF) }
}
