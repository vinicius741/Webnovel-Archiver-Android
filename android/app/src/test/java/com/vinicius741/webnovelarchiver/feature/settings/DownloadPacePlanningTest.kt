package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadPacePlanningTest {
    @Test
    fun movingOneEndPastTheOtherDragsItAlong() {
        val range = PaceRange(800, 2_500)
        assertEquals(PaceRange(3_000, 3_000), DownloadPacePlanning.withMin(range, 3_000))
        assertEquals(PaceRange(1_000, 2_500), DownloadPacePlanning.withMin(range, 1_000))
        assertEquals(PaceRange(500, 500), DownloadPacePlanning.withMax(range, 500))
        assertEquals(PaceRange(800, 4_000), DownloadPacePlanning.withMax(range, 4_000))
    }

    @Test
    fun stepperLimitGrowsToFitALargerSavedDelayButNeverShrinks() {
        assertEquals(DownloadPacePlanning.STEPPER_MAX_MS, DownloadPacePlanning.stepperMaxMs(PaceRange(800, 2_500)))
        assertEquals(DownloadPacePlanning.STEPPER_MAX_MS, DownloadPacePlanning.stepperMaxMs(PaceRange(0, 120_000)))
        assertEquals(300_000L, DownloadPacePlanning.stepperMaxMs(PaceRange(1_000, 300_000)))
        assertEquals(180_000L, DownloadPacePlanning.stepperMaxMs(PaceRange(180_000, 150_000)))
    }

    @Test
    fun overrideIsReadByIdThenLegacyName() {
        val byName = mapOf("Royal Road" to SourceDownloadSettings(delay = 1_000, delayMax = 2_000))
        assertEquals(1_000L, DownloadPacePlanning.overrideFor(byName, "royalroad", "Royal Road")?.delay)
        val both = byName + ("royalroad" to SourceDownloadSettings(delay = 3_000, delayMax = 3_000))
        assertEquals(3_000L, DownloadPacePlanning.overrideFor(both, "royalroad", "Royal Road")?.delay)
        assertNull(DownloadPacePlanning.overrideFor(emptyMap(), "royalroad", "Royal Road"))
    }

    @Test
    fun savingACustomPaceStoresItUnderTheIdOnly() {
        val before =
            mapOf(
                "Royal Road" to SourceDownloadSettings(delay = 1_000, delayMax = 2_000),
                "scribblehub" to SourceDownloadSettings(delay = 400, delayMax = 400),
            )
        val after = DownloadPacePlanning.withOverride(before, "royalroad", "Royal Road", PaceRange(1_500, 4_200))
        assertEquals(setOf("royalroad", "scribblehub"), after.keys)
        assertEquals(SourceDownloadSettings(concurrency = 1, delay = 1_500, delayMax = 4_200), after["royalroad"])
    }

    @Test
    fun usingTheDefaultsRemovesBothKeys() {
        val before =
            mapOf(
                "Royal Road" to SourceDownloadSettings(),
                "royalroad" to SourceDownloadSettings(),
            )
        assertEquals(emptyMap<String, SourceDownloadSettings>(), DownloadPacePlanning.withOverride(before, "royalroad", "Royal Road", null))
    }

    @Test
    fun presetsMatchOnlyExactRanges() {
        assertEquals("Balanced", DownloadPacePlanning.matchingPreset(PaceRange(1_000, 3_000))?.label)
        assertNull(DownloadPacePlanning.matchingPreset(PaceRange(1_000, 3_100)))
    }

    @Test
    fun labelsUsePlainSeconds() {
        assertEquals("0.8", DownloadPacePlanning.seconds(800))
        assertEquals("2", DownloadPacePlanning.seconds(2_000))
        assertEquals("1.25", DownloadPacePlanning.seconds(1_250))
        assertEquals("0", DownloadPacePlanning.seconds(0))
        assertEquals("0.8–2.5 s", DownloadPacePlanning.rangeLabel(PaceRange(800, 2_500)))
        assertEquals("2 s", DownloadPacePlanning.rangeLabel(PaceRange(2_000, 2_000)))
        assertEquals("Uses the defaults", DownloadPacePlanning.overrideSummary(null))
        assertEquals("2–5 s delay", DownloadPacePlanning.overrideSummary(SourceDownloadSettings(delay = 2_000, delayMax = 5_000)))
    }
}
