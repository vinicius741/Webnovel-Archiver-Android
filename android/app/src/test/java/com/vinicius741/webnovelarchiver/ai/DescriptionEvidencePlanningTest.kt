package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionEvidencePlanningTest {
    private companion object {
        const val MODEL = "typesafe/jev-1.13"
    }

    private val sample = ChapterEvidencePlanning.ChapterSample(1, "Opening", "Mara inherits a failing orchard.", "")

    @Test fun `scan reads the opening in order and stops at the window`() {
        assertEquals(emptyList<Int>(), DescriptionEvidencePlanning.scanOrder(0))
        assertEquals(listOf(0, 1, 2), DescriptionEvidencePlanning.scanOrder(3))
        assertEquals((0 until DescriptionEvidencePlanning.MAX_SCANNED_CHAPTERS).toList(), DescriptionEvidencePlanning.scanOrder(500))
    }

    @Test fun `background uses only the source description so a previous AI draft never feeds the scan`() {
        val story = Story(title = "Orchard", description = "Source blurb", aiDescription = "AI blurb", showAiDescription = true)
        assertEquals("Source blurb", DescriptionEvidencePlanning.state(story, sample).get("description").asString)
        val aiOnly = story.copy(description = " ")
        assertTrue(DescriptionEvidencePlanning.state(aiOnly, sample).get("description").isJsonNull)
    }

    @Test fun `request asks premise questions and hashes differently from the cover rubric`() {
        val story = Story(title = "Orchard", description = "Source blurb")
        val body = DescriptionEvidencePlanning.request(story, sample, MODEL)
        assertEquals(setOf("protagonist", "conflict", "setting", "spoiler"), body.getAsJsonObject("questions").keySet())
        assertEquals(
            4,
            body
                .getAsJsonObject("questions")
                .getAsJsonObject("protagonist")
                .getAsJsonArray("criteria")
                .size(),
        )
        assertNotEquals(
            ChapterEvidencePlanning.cacheKey(CoverEvidencePlanning.request(story.copy(aiDescription = null), sample, MODEL)),
            ChapterEvidencePlanning.cacheKey(body),
        )
    }

    @Test fun `useful chapters establish the protagonist or conflict without spoiling a twist`() {
        fun judged(
            protagonist: Double,
            conflict: Double,
            spoiler: Double = 0.0,
        ) = DescriptionEvidencePlanning.Judgments(protagonist, conflict, 0.0, spoiler)
        assertTrue(DescriptionEvidencePlanning.isUseful(judged(0.7, 0.0)))
        assertTrue(DescriptionEvidencePlanning.isUseful(judged(0.0, 1.0, spoiler = 0.5)))
        // Generic mentions and likely spoilers never qualify; setting alone is not a premise.
        assertFalse(DescriptionEvidencePlanning.isUseful(judged(0.6, 0.6)))
        assertFalse(DescriptionEvidencePlanning.isUseful(judged(1.0, 1.0, spoiler = 0.6)))
        assertFalse(DescriptionEvidencePlanning.isUseful(DescriptionEvidencePlanning.Judgments(0.0, 0.0, 1.0, 0.0)))
        assertTrue(DescriptionEvidencePlanning.utility(judged(1.0, 1.0)) > DescriptionEvidencePlanning.utility(judged(1.0, 1.0, 0.4)))
    }

    @Test fun `assessment parses answers and rejects malformed ones`() {
        val json =
            JsonParser
                .parseString(
                    """{"answers":{"protagonist":{"type":"score","score":3},"conflict":{"type":"score","score":1.5},
                    "setting":{"score":0},"spoiler":{"type":"noul","noul":0.2}}}""",
                ).asJsonObject
        val parsed = DescriptionEvidencePlanning.parse(json)
        assertEquals(1.0, parsed.protagonist, 0.0)
        assertEquals(0.5, parsed.conflict, 0.0)
        assertEquals(0.2, parsed.spoiler, 0.0)
        assertTrue(DescriptionEvidencePlanning.assess(sample, json).useful)
        val missing = json.deepCopy().apply { getAsJsonObject("answers").remove("spoiler") }
        assertTrue(runCatching { DescriptionEvidencePlanning.assess(sample, missing) }.isFailure)
    }
}
