package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsPcmPlanningTest {
    private fun wav(
        pcm: ByteArray,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        bits: Int = 16,
        extraChunk: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun int32(value: Int) =
            out.write(
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value)
                    .array(),
            )

        fun int16(value: Int) =
            out.write(
                ByteBuffer
                    .allocate(2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(value.toShort())
                    .array(),
            )
        out.write("RIFF".toByteArray())
        int32(0)
        out.write("WAVE".toByteArray())
        if (extraChunk) {
            out.write("LIST".toByteArray())
            int32(3)
            out.write(byteArrayOf(1, 2, 3, 0))
        }
        out.write("fmt ".toByteArray())
        int32(16)
        int16(1)
        int16(channels)
        int32(sampleRate)
        int32(sampleRate * channels * bits / 8)
        int16(channels * bits / 8)
        int16(bits)
        out.write("data".toByteArray())
        int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun samples(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    @Test
    fun parsesMonoPcmEvenPastOtherChunks() {
        val pcm = samples(1, 2, 3)
        val clip = assertNotNullClip(TtsPcmPlanning.parseWav(wav(pcm, extraChunk = true)))
        assertEquals(24_000, clip.sampleRate)
        assertArrayEquals(pcm, clip.pcm)
    }

    @Test
    fun rejectsWhatThePlayerCannotPlay() {
        assertNull(TtsPcmPlanning.parseWav(wav(samples(1, 2), channels = 2)))
        assertNull(TtsPcmPlanning.parseWav(wav(samples(1, 2), bits = 8)))
        assertNull(TtsPcmPlanning.parseWav("not a wav file at all".toByteArray()))
        assertNull(TtsPcmPlanning.parseWav(ByteArray(0)))
    }

    @Test
    fun zeroDataLengthFromAStreamingEngineUsesWhatIsThere() {
        val bytes = wav(samples(5, 6))
        // The data chunk's length field sits right before its 4 audio bytes.
        val lengthAt = bytes.size - 8
        for (index in 0 until 4) bytes[lengthAt + index] = 0
        assertEquals(2, assertNotNullClip(TtsPcmPlanning.parseWav(bytes)).frames)
    }

    @Test
    fun trimsLeadAndTailDownToAShortPadding() {
        val rate = 8_000
        val silence = IntArray(rate) { 0 }
        val speech = IntArray(rate) { 10_000 }
        val clip = TtsPcmClip(rate, samples(*silence, *speech, *silence))

        val trimmed = TtsPcmPlanning.trimSilence(clip)

        val paddingFrames = TtsPcmPlanning.msToFrames(15L, rate).toInt()
        assertEquals(rate + paddingFrames * 2, trimmed.frames)
        assertTrue(trimmed.durationMs < clip.durationMs / 2)
    }

    @Test
    fun allSilenceTrimsToNothing() {
        assertEquals(0, TtsPcmPlanning.trimSilence(TtsPcmClip(8_000, samples(0, 1, -1, 0))).frames)
    }

    @Test
    fun fadesAvoidAClickAtTheCut() {
        val rate = 8_000
        val trimmed = TtsPcmPlanning.trimSilence(TtsPcmClip(rate, samples(*IntArray(rate) { 10_000 })))
        val first = ByteBuffer.wrap(trimmed.pcm).order(ByteOrder.LITTLE_ENDIAN).short
        assertEquals(0, first.toInt())
    }

    private fun assertNotNullClip(clip: TtsPcmClip?): TtsPcmClip {
        assertNotNull(clip)
        return clip!!
    }

    private fun speechWithGaps(vararg parts: Pair<Long, Boolean>): TtsPcmClip {
        val out = ByteArrayOutputStream()
        parts.forEach { (ms, loud) ->
            repeat(TtsPcmPlanning.msToFrames(ms, 24_000).toInt()) {
                out.write(if (loud) 0xE8 else 0)
                out.write(if (loud) 0x03 else 0)
            }
        }
        return TtsPcmClip(24_000, out.toByteArray())
    }

    @Test
    fun phraseBreaksFallInTheMiddleOfLongGapsOnly() {
        val clip =
            speechWithGaps(
                100L to true,
                40L to false,
                100L to true,
                120L to false,
                100L to true,
            )
        // The 40 ms gap is a consonant's closure; the 120 ms gap is a phrase. 100+40+100+60 = 300 ms.
        assertEquals(listOf(TtsPcmPlanning.msToFrames(300L, 24_000).toInt()), TtsPcmPlanning.phraseBreaks(clip))
    }

    @Test
    fun silenceAtTheEdgesIsNotAPhraseBreak() {
        val clip = speechWithGaps(200L to false, 100L to true, 200L to false)
        assertTrue(TtsPcmPlanning.phraseBreaks(clip).isEmpty())
    }
}
