package com.vinicius741.webnovelarchiver.feature.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSummaryPlanningTest {
    @Test
    fun `changed chapters explain the removals and ask for regeneration`() {
        val summary =
            CleanupSummaryPlanning.summarize(
                CleanupRunStats(
                    totalChapters = 42,
                    processedChapters = 42,
                    failedChapters = 0,
                    changedChapters = 5,
                    sentencesRemoved = 12,
                    regexMatchesRemoved = 1,
                ),
            )

        assertEquals("Text Cleanup Complete", summary.title)
        assertEquals(
            "Checked 42 chapters against your sentence removal list and regex cleanup rules.\n\n" +
                "Removed 13 matches from 5 chapters:\n" +
                "• 12 sentences from the sentence removal list\n" +
                "• 1 regex rule match\n\n" +
                "Regenerate the EPUB to include these changes.",
            summary.message,
        )
    }

    @Test
    fun `nothing found does not ask for regeneration`() {
        val summary =
            CleanupSummaryPlanning.summarize(
                CleanupRunStats(
                    totalChapters = 1,
                    processedChapters = 1,
                    failedChapters = 0,
                    changedChapters = 0,
                    sentencesRemoved = 0,
                    regexMatchesRemoved = 0,
                ),
            )

        assertEquals("Text Cleanup: Nothing to Remove", summary.title)
        assertEquals(
            "Checked 1 chapter against your sentence removal list and regex cleanup rules.\n\n" +
                "No rule matched any text, so no chapters were changed.",
            summary.message,
        )
        assertFalse(summary.message.contains("EPUB"))
    }

    @Test
    fun `failures are explained and regeneration is asked only when something changed`() {
        val unchanged =
            CleanupSummaryPlanning.summarize(
                CleanupRunStats(
                    totalChapters = 10,
                    processedChapters = 8,
                    failedChapters = 2,
                    changedChapters = 0,
                    sentencesRemoved = 0,
                    regexMatchesRemoved = 0,
                ),
            )
        assertEquals("Text Cleanup Finished with Errors", unchanged.title)
        assertTrue(
            unchanged.message.startsWith(
                "Checked 8 of 10 chapters. 2 chapters couldn't be processed because their files are missing or unreadable.",
            ),
        )
        assertFalse(unchanged.message.contains("EPUB"))

        val changed =
            CleanupSummaryPlanning.summarize(
                CleanupRunStats(
                    totalChapters = 10,
                    processedChapters = 9,
                    failedChapters = 1,
                    changedChapters = 3,
                    sentencesRemoved = 0,
                    regexMatchesRemoved = 4,
                ),
            )
        assertTrue(changed.message.contains("1 chapter couldn't be processed because its file is missing or unreadable."))
        assertTrue(changed.message.endsWith("Regenerate the EPUB to include these changes."))
    }

    @Test
    fun `formatting-only changes still ask for regeneration`() {
        val summary =
            CleanupSummaryPlanning.summarize(
                CleanupRunStats(
                    totalChapters = 2,
                    processedChapters = 2,
                    failedChapters = 0,
                    changedChapters = 1,
                    sentencesRemoved = 0,
                    regexMatchesRemoved = 0,
                ),
            )

        assertEquals("Text Cleanup Complete", summary.title)
        assertTrue(summary.message.contains("leftover formatting was cleaned up in 1 chapter."))
        assertTrue(summary.message.endsWith("Regenerate the EPUB to include these changes."))
    }
}
