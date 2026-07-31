package com.vinicius741.webnovelarchiver.feature.story

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubOpenPlanningTest {
    @Test
    fun installedReaderLaunchesViewIntent() {
        assertEquals(EpubOpenPlan.LAUNCH, planEpubOpen(hasEpubReader = true))
    }

    @Test
    fun missingReaderShowsRequiredReaderNotice() {
        assertEquals(EpubOpenPlan.SHOW_READER_REQUIRED, planEpubOpen(hasEpubReader = false))
    }
}
