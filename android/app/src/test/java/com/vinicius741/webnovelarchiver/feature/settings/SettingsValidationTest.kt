package com.vinicius741.webnovelarchiver.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun concurrencyStaysWithinBounds() {
        assertEquals(1, SettingsValidation.concurrency(""))
        assertEquals(1, SettingsValidation.concurrency("0"))
        assertEquals(5, SettingsValidation.concurrency("5"))
        assertEquals(10, SettingsValidation.concurrency("99"))
    }

    @Test
    fun delayClampsAtZero() {
        assertEquals(500L, SettingsValidation.delay(""))
        assertEquals(0L, SettingsValidation.delay("-50"))
        assertEquals(1200L, SettingsValidation.delay("1200"))
    }

    @Test
    fun epubAndTtsValuesStayInSupportedRanges() {
        assertEquals(10, SettingsValidation.maxChaptersPerEpub("0"))
        assertEquals(1000, SettingsValidation.maxChaptersPerEpub("5000"))
        assertEquals(150, SettingsValidation.maxChaptersPerEpub(""))
        assertEquals(0.5f, SettingsValidation.ttsScalar("0.1"), 0.0f)
        assertEquals(2.0f, SettingsValidation.ttsScalar("3"), 0.0f)
        assertEquals(100, SettingsValidation.ttsChunkSize("20"))
    }
}
