package com.vinicius741.webnovelarchiver.narration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NarrationClipFormatTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clip =
        NarrationEncodedClip(
            codec = NarrationClipFormat.CODEC_AAC_LC,
            sampleRate = 24_000,
            frames = 4_800,
            delayFrames = 2_048,
            setup = byteArrayOf(0x13, 0x08),
            // Seven units of 1024 frames cover the delay and the clip.
            units = listOf(byteArrayOf(1, 2, 3), ByteArray(0)) + List(5) { byteArrayOf(9) },
        )

    @Test
    fun aCompressedClipReadsBackAsWritten() {
        val bytes = NarrationClipFormat.write(clip)
        assertTrue(NarrationClipFormat.isCompressed(bytes))
        val read = NarrationClipFormat.read(bytes)!!
        assertEquals(clip.codec, read.codec)
        assertEquals(24_000, read.sampleRate)
        assertEquals(4_800, read.frames)
        assertEquals(2_048, read.delayFrames)
        assertArrayEquals(clip.setup, read.setup)
        assertEquals(clip.units.map(ByteArray::toList), read.units.map(ByteArray::toList))
    }

    @Test
    fun legacyPcmIsNeverMistakenForACompressedClip() {
        for (rate in listOf(8_000, 22_050, 24_000, 44_100, 48_000)) {
            assertFalse(NarrationClipFormat.isCompressed(NarrationClipFormat.legacy(rate, ByteArray(20))))
        }
    }

    @Test
    fun aDamagedClipIsRejected() {
        val bytes = NarrationClipFormat.write(clip)
        assertNull(NarrationClipFormat.read(bytes.copyOf(bytes.size - 1)))
        assertNull(NarrationClipFormat.read(bytes.copyOf(10)))
        assertNull(NarrationClipFormat.read(NarrationClipFormat.legacy(24_000, ByteArray(40))))
    }

    @Test
    fun aClipWhoseUnitsStopShortOfItsFramesIsRejected() {
        val short = NarrationEncodedClip(clip.codec, clip.sampleRate, clip.frames, clip.delayFrames, clip.setup, clip.units.dropLast(1))
        assertFalse(NarrationClipFormat.covers(short))
        assertNull(NarrationClipFormat.read(NarrationClipFormat.write(short)))
        assertTrue(NarrationClipFormat.covers(NarrationEncodedClip(clip.codec, 24_000, 0, 0, ByteArray(0), emptyList())))
    }

    @Test
    fun infoRejectsAHeaderReadWouldReject() {
        val bytes = NarrationClipFormat.write(clip)
        val newerVersion = bytes.copyOf().also { it[4] = 2 }
        val noRate = bytes.copyOf().also { it.fill(0, 6, 10) }
        for (damaged in listOf(newerVersion, noRate, bytes.copyOf(16))) {
            assertNull(NarrationClipFormat.info(File(folder.root, "x.clip").apply { writeBytes(damaged) }))
        }
    }

    @Test
    fun infoReadsBothLayoutsFromTheHeader() {
        val compressed = File(folder.root, "a.clip").apply { writeBytes(NarrationClipFormat.write(clip)) }
        val legacy = File(folder.root, "b.pcm").apply { writeBytes(NarrationClipFormat.legacy(22_050, ByteArray(882))) }
        assertEquals(
            NarrationClipInfo(24_000, 4_800, compressed = true, codec = NarrationClipFormat.CODEC_AAC_LC),
            NarrationClipFormat.info(compressed),
        )
        assertEquals(NarrationClipInfo(22_050, 441, compressed = false, codec = null), NarrationClipFormat.info(legacy))
        assertNull(NarrationClipFormat.info(File(folder.root, "missing")))
        assertNull(NarrationClipFormat.info(File(folder.root, "short").apply { writeBytes(byteArrayOf(1, 2)) }))
    }

    @Test
    fun trimSkipsTheDelayAndKeepsExactlyTheClip() {
        val decoded = ByteArray(20) { it.toByte() }
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), NarrationClipFormat.trim(decoded, delayFrames = 2, frames = 2))
        // A decoder that stops short is padded with silence rather than shortening the clip.
        assertArrayEquals(byteArrayOf(16, 17, 18, 19, 0, 0), NarrationClipFormat.trim(decoded, delayFrames = 8, frames = 3))
        assertArrayEquals(ByteArray(4), NarrationClipFormat.trim(decoded, delayFrames = 50, frames = 2))
    }

    @Test
    fun theProbeFindsTheCodecDelay() {
        val probe = NarrationCodecProbe.signal(12_000)
        for (lag in listOf(0, 1, 1_024, 2_048, 2_112)) {
            // Delayed, attenuated and noisy, as a lossy codec returns it.
            val decoded = ByteArray(lag * 2) + attenuate(probe) + ByteArray(4_000)
            assertEquals(lag, NarrationCodecProbe.delay(probe, decoded, 6_000))
        }
        assertNotNull(NarrationCodecProbe.delay(probe, probe, 6_000))
        assertNull(NarrationCodecProbe.delay(probe, ByteArray(probe.size), 6_000))
    }

    private fun attenuate(pcm: ByteArray): ByteArray {
        val out = pcm.copyOf()
        for (i in out.indices step 2) {
            val sample = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)) * 7 / 10 + (i % 7) - 3
            out[i] = sample.toByte()
            out[i + 1] = (sample shr 8).toByte()
        }
        return out
    }
}
