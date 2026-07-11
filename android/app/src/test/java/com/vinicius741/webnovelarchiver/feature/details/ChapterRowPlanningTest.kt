package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChapterRowPlanningTest {
    @Test
    fun indexLabelIsOneBasedWithoutTrailingPeriod() {
        assertEquals("1", ChapterRowPlanning.indexLabel(0))
        assertEquals("13", ChapterRowPlanning.indexLabel(12))
        assertEquals("100", ChapterRowPlanning.indexLabel(99))
    }

    @Test
    fun displayTitleDoesNotPrefixIndexAndStripsTrailingEllipsis() {
        assertEquals("13.11 The First Step", ChapterRowPlanning.displayTitle("13.11 The First Step..."))
        assertEquals("Prologue", ChapterRowPlanning.displayTitle("Prologue"))
    }

    @Test
    fun subtitlePrefersLiveDownloadStatus() {
        assertEquals(
            "Downloading…",
            ChapterRowPlanning.subtitle(DownloadJobStatus.Downloading, downloaded = false, downloadedAt = null),
        )
        assertEquals(
            "Queued",
            ChapterRowPlanning.subtitle(DownloadJobStatus.Pending, downloaded = false, downloadedAt = null),
        )
        assertEquals(
            "Download failed",
            ChapterRowPlanning.subtitle(DownloadJobStatus.Failed, downloaded = false, downloadedAt = null),
        )
    }

    @Test
    fun subtitleShowsDownloadDateWhenKnown() {
        val noon =
            LocalDate
                .of(2026, 7, 10)
                .atTime(12, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        assertEquals(
            "Downloaded Jul 10, 2026",
            ChapterRowPlanning.subtitle(null, downloaded = true, downloadedAt = noon),
        )
    }

    @Test
    fun subtitleFallsBackToOfflineCueForLegacyDownloadsWithoutTimestamp() {
        assertEquals(
            "Available Offline",
            ChapterRowPlanning.subtitle(null, downloaded = true, downloadedAt = null),
        )
        assertNull(ChapterRowPlanning.subtitle(null, downloaded = false, downloadedAt = null))
    }

    @Test
    fun indexDigitCountGrowsWithChapterTotal() {
        assertEquals(1, ChapterRowPlanning.indexDigitCount(9))
        assertEquals(2, ChapterRowPlanning.indexDigitCount(10))
        assertEquals(3, ChapterRowPlanning.indexDigitCount(100))
        assertTrue(ChapterRowPlanning.indexDigitCount(0) >= 1)
    }
}
