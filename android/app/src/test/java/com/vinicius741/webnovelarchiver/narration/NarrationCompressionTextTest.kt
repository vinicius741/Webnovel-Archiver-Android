package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationCompressionTextTest {
    private val gb = 1024L * 1024 * 1024

    private fun describe(status: NarrationCompressionStatus) = NarrationCompressionText.describe(status, totalBytes = 8 * gb) { "4:35 PM" }

    @Test
    fun saysNothingBeforeStartingOrWhenThereWasNothingToDo() {
        assertNull(describe(NarrationCompressionStatus()))
        assertNull(describe(NarrationCompressionStatus(Phase.DONE)))
    }

    @Test
    fun compressingTellsWhatIsLeftAndWhereTheAudioEnds() {
        // 4 GB of PCM converted to 0.7 GB so far; 4 GB left shrinks by the same ratio.
        val status =
            NarrationCompressionStatus(
                Phase.COMPRESSING,
                remainingChapters = 60,
                remainingBytes = 4 * gb,
                convertedChapters = 60,
                convertedBytesBefore = 4 * gb,
                savedBytes = 4 * gb - 7 * gb / 10,
            )
        val lines = describe(status)!!
        assertEquals("Compressing older audio · 60 chapters left (4.0 GB)", lines.headline)
        assertEquals("Saved 3.3 GB so far · audio ends near 4.7 GB", lines.detail)
        assertEquals(0.5f, status.fraction!!, 0.001f)
    }

    @Test
    fun aPauseSaysWhenItTriesAgain() {
        val lines = describe(NarrationCompressionStatus(Phase.PAUSED, remainingChapters = 1, remainingBytes = gb, retryAt = 1L))!!
        assertEquals("Compression paused after codec errors · 1 chapter left (1.0 GB)", lines.headline)
        assertTrue(lines.detail, lines.detail.startsWith("Trying again at 4:35 PM · "))
    }

    @Test
    fun finishedReportsChaptersLeftUncompressed() {
        val lines = describe(NarrationCompressionStatus(Phase.DONE, convertedChapters = 3, savedBytes = gb, failedChapters = 2))!!
        assertEquals("2 chapters could not be compressed", lines.headline)
    }
}
