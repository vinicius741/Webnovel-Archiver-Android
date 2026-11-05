package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTimeFormatTest {
    @Test
    fun clockGrowsAnHourFieldOnlyWhenNeeded() {
        assertEquals("0:05", TtsTimeFormat.clock(5_000L))
        assertEquals("12:34", TtsTimeFormat.clock(754_000L))
        assertEquals("1:02:03", TtsTimeFormat.clock(3_723_000L))
        assertEquals("0:00", TtsTimeFormat.clock(-1L))
    }

    @Test
    fun timeLeftReflectsTheCurrentSpeedAndRoundsUp() {
        assertEquals("10 min left", TtsTimeFormat.timeLeft(0L, 600_000L, 1f))
        assertEquals("5 min left", TtsTimeFormat.timeLeft(0L, 600_000L, 2f))
        assertEquals("1 min left", TtsTimeFormat.timeLeft(599_000L, 600_000L, 1f))
        assertEquals("1 h left", TtsTimeFormat.timeLeft(0L, 3_600_000L, 1f))
        assertEquals("1 h 30 min left", TtsTimeFormat.timeLeft(0L, 5_400_000L, 1f))
    }
}
