package com.vinicius741.webnovelarchiver.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHubPlanningTest {
    @Test
    fun voiceSummaryShortensEngineAndVoiceAndSkipsNormalRate() {
        assertEquals("Kokoro · Bella · 1.8×", SettingsHubPlanning.voiceSummary("Kokoro (built in)", "Bella (American, female)", 1.8f))
        assertEquals("Google · en-us-x-sfg", SettingsHubPlanning.voiceSummary("Google", "en-us-x-sfg", 1f))
    }

    @Test
    fun rateLabelDropsTrailingZeros() {
        assertEquals("1.25×", SettingsHubPlanning.rateLabel(1.25f))
        assertEquals("2×", SettingsHubPlanning.rateLabel(2f))
        assertEquals("0.5×", SettingsHubPlanning.rateLabel(0.5f))
    }

    @Test
    fun summariesCountInPlainWords() {
        assertEquals("No rules yet", SettingsHubPlanning.cleanupSummary(0, 0))
        assertEquals("1 rule · 12 sentences", SettingsHubPlanning.cleanupSummary(1, 12))
        assertEquals("No tabs yet", SettingsHubPlanning.tabsSummary(emptyList()))
        assertEquals("2 tabs · Reading, Plan", SettingsHubPlanning.tabsSummary(listOf("Reading", "Plan")))
        assertEquals("5 tabs · A, B, C, D, …", SettingsHubPlanning.tabsSummary(listOf("A", "B", "C", "D", "E")))
        assertEquals("1 source at once · 0.8–2.5 s delay", SettingsHubPlanning.downloadsSummary(1, PaceRange(800, 2_500)))
    }
}
