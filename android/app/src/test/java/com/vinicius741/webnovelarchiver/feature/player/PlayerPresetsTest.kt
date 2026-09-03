package com.vinicius741.webnovelarchiver.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPresetsTest {
    @Test
    fun nextRatePresetCyclesThroughAllValuesAndWraps() {
        var rate = 0.75f
        rate = nextRatePreset(rate)
        assertEquals(1.0f, rate, 0.001f)
        rate = nextRatePreset(rate)
        assertEquals(1.25f, rate, 0.001f)
        rate = nextRatePreset(rate)
        assertEquals(1.5f, rate, 0.001f)
        rate = nextRatePreset(rate)
        assertEquals(1.75f, rate, 0.001f)
        rate = nextRatePreset(rate)
        assertEquals(2.0f, rate, 0.001f)
        rate = nextRatePreset(rate)
        assertEquals(0.75f, rate, 0.001f)
    }

    @Test
    fun rateLabelFormatsWholeAndDecimalNumbers() {
        assertEquals("1x", rateLabel(1.0f))
        assertEquals("2x", rateLabel(2.0f))
        assertEquals("1.25x", rateLabel(1.25f))
        assertEquals("1.5x", rateLabel(1.5f))
        assertEquals("0.75x", rateLabel(0.75f))
    }

    @Test
    fun sleepTimerLabelFormatsModesCorrectly() {
        assertEquals("Timer: Off", sleepTimerLabel(null, false))
        assertEquals("Timer: Ch. End", sleepTimerLabel(null, true))
        val target = System.currentTimeMillis() + 15 * 60_000L
        assertEquals("Timer: 15m", sleepTimerLabel(target, false))
    }
}
