package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroThreadsTest {
    @Test
    fun `every core of a chip with only fast cores gets a thread`() {
        // Snapdragon 8 Elite (Galaxy Z Fold7): six 3.53 GHz cores and two 4.47 GHz cores.
        assertEquals(8, KokoroThreads.forClocks(List(6) { 3_532_800L } + List(2) { 4_473_600L }))
    }

    @Test
    fun `efficiency cores are left out`() {
        // Snapdragon 8 Gen 3: 2.27 GHz efficiency cores beside 2.96, 3.15 and 3.30 GHz ones.
        assertEquals(6, KokoroThreads.forClocks(List(2) { 2_265_600L } + List(2) { 2_956_800L } + List(3) { 3_148_800L } + 3_302_400L))
        // Tensor G3: four 1.70 GHz, four 2.37 GHz and one 2.91 GHz core.
        assertEquals(5, KokoroThreads.forClocks(List(4) { 1_704_000L } + List(4) { 2_367_000L } + 2_914_000L))
    }

    @Test
    fun `the count stays between two and eight`() {
        assertEquals(8, KokoroThreads.forClocks(List(12) { 3_000_000L }))
        assertEquals(2, KokoroThreads.forClocks(listOf(1_000_000L, 3_000_000L)))
    }

    @Test
    fun `unreadable or placeholder clocks fall back to the old fixed count`() {
        assertEquals(KokoroThreads.FALLBACK, KokoroThreads.forClocks(emptyList()))
        assertEquals(KokoroThreads.FALLBACK, KokoroThreads.forClocks(listOf(3_000_000L, null, 3_000_000L)))
        // The emulator reports top clocks below 1 MHz.
        assertEquals(KokoroThreads.FALLBACK, KokoroThreads.forClocks(List(4) { 999L }))
    }
}
