package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChapterPolishPlanningTest {
    private fun chapter(
        id: String,
        title: String = "",
        downloaded: Boolean = true,
    ): Chapter = Chapter(id = id, title = title, downloaded = downloaded)

    private fun draft(
        chapterId: String,
        status: String,
    ): ChapterRewriteDraftRecord = ChapterRewriteDraftRecord(chapterId = chapterId, status = status)

    // ---------------------------------------------------------------- summarize + line

    @Test
    fun summarizeCountsDraftsAndAppliedByStatus() {
        val summary =
            AiChapterPolishPlanning.summarize(
                drafts = mapOf("a" to draft("a", "ready"), "b" to draft("b", "blocked"), "c" to draft("c", "verify_failed")),
                applied =
                    mapOf(
                        "d" to AppliedChapterRewrite(chapterId = "d", active = true),
                        "e" to AppliedChapterRewrite(chapterId = "e", active = false),
                    ),
            )
        assertEquals(1, summary.appliedActive)
        assertEquals(1, summary.appliedInactive)
        assertEquals(1, summary.draftsReady)
        assertEquals(2, summary.draftsBlocked)
    }

    @Test
    fun lineOmitsZeroPartsAndJoinsTheRest() {
        assertEquals(
            "3 polished · 2 drafts ready · 1 flagged",
            AiChapterPolishPlanning.Summary(appliedActive = 2, appliedInactive = 1, draftsReady = 2, draftsBlocked = 1).line(),
        )
        assertEquals(
            "1 draft ready",
            AiChapterPolishPlanning.Summary(0, 0, 1, 0).line(),
        )
        assertNull(AiChapterPolishPlanning.Summary(0, 0, 0, 0).line())
    }

    @Test
    fun lineUsesSingularDraft() {
        assertEquals(
            "1 polished · 1 draft ready",
            AiChapterPolishPlanning.Summary(appliedActive = 1, appliedInactive = 0, draftsReady = 1, draftsBlocked = 0).line(),
        )
    }

    // ---------------------------------------------------------------- matchesFilter

    @Test
    fun filterCategoriesMatchStatusTags() {
        val cases =
            mapOf(
                AiChapterPolishPlanning.STATUS_DRAFT_READY to "ready",
                AiChapterPolishPlanning.STATUS_DRAFT_BLOCKED to "flagged",
                AiChapterPolishPlanning.STATUS_APPLIED_ACTIVE to "polished",
                AiChapterPolishPlanning.STATUS_APPLIED_INACTIVE to "polished",
                AiChapterPolishPlanning.STATUS_GENERATING to "all",
                null to "unpolished",
            )
        cases.forEach { (status, expectedFilter) ->
            listOf("all", "ready", "flagged", "polished", "unpolished").forEach { filter ->
                val expected = filter == "all" || filter == expectedFilter
                assertEquals("status=$status filter=$filter", expected, AiChapterPolishPlanning.matchesFilter(status, filter))
            }
        }
    }

    // ---------------------------------------------------------------- filterChapters

    @Test
    fun filterChaptersSearchesChapterNumberAndTitle() {
        val chapters = listOf(chapter("a", "The Beginning"), chapter("b", "The Middle"), chapter("c", "The End"))
        val pairs = chapters.mapIndexed { index, ch -> index to ch }
        assertEquals(1, AiChapterPolishPlanning.filterChapters(pairs, { null }, "beginning", "all").size)
        assertEquals(listOf(1), AiChapterPolishPlanning.filterChapters(pairs, { null }, "chapter 2", "all").map { it.first })
        assertEquals(3, AiChapterPolishPlanning.filterChapters(pairs, { null }, "", "all").size)
    }

    @Test
    fun filterChaptersCombinesSearchAndStatus() {
        val chapters = listOf(chapter("a", "Alpha"), chapter("b", "Beta"))
        val pairs = chapters.mapIndexed { index, ch -> index to ch }
        val statuses = mapOf("a" to AiChapterPolishPlanning.STATUS_APPLIED_ACTIVE)
        val result = AiChapterPolishPlanning.filterChapters(pairs, { statuses[it] }, "", "unpolished")
        assertEquals(listOf("b"), result.map { it.second.id })
    }

    // ---------------------------------------------------------------- nextUnpolished

    @Test
    fun nextUnpolishedSkipsNonDownloadedAndStatusedChaptersInOrder() {
        val chapters =
            listOf(
                chapter("a", downloaded = true),
                chapter("b", downloaded = false),
                chapter("c", downloaded = true),
                chapter("d", downloaded = true),
            )
        val statuses = mapOf("a" to AiChapterPolishPlanning.STATUS_APPLIED_ACTIVE, "d" to AiChapterPolishPlanning.STATUS_QUEUED)
        val result = AiChapterPolishPlanning.nextUnpolished(chapters, { statuses[it] }, 25)
        assertEquals(listOf("c"), result.map { it.id })
    }

    @Test
    fun nextUnpolishedRespectsLimit() {
        val chapters = (1..10).map { chapter("ch$it") }
        assertEquals(3, AiChapterPolishPlanning.nextUnpolished(chapters, { null }, 3).size)
    }

    // ---------------------------------------------------------------- known-good rewrite models

    @Test
    fun knownGoodMatchesSpikeModelsAcrossPrefixes() {
        assertTrue(AiModelPresentation.isKnownGoodRewriteModel("openai/gpt-5.6-terra"))
        assertTrue(AiModelPresentation.isKnownGoodRewriteModel("deepseek/deepseek-v4-pro-0813"))
        assertTrue(AiModelPresentation.isKnownGoodRewriteModel("GPT-5.6-SOL"))
        assertTrue(AiModelPresentation.isKnownGoodRewriteModel("x-ai/grok-4.6"))
    }

    @Test
    fun unknownModelsAreNotKnownGood() {
        assertFalse(AiModelPresentation.isKnownGoodRewriteModel("openai/gpt-4o-mini"))
        assertFalse(AiModelPresentation.isKnownGoodRewriteModel(""))
    }
}
