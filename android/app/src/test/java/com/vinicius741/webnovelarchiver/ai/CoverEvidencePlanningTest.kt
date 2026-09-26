package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverEvidencePlanningTest {
    @Test fun `sampling takes the opening and a mid chapter window and skips blank text`() {
        val text = (1..600).joinToString("\n\n") { "Paragraph $it contains an important description." }
        val sample = CoverEvidencePlanning.sample(17, "Later", text)!!
        assertTrue(sample.opening.contains("Paragraph 1"))
        assertTrue(sample.middle.isNotEmpty())
        assertTrue(sample.opening.length <= 2_200 && sample.middle.length <= 2_200)
        val short = CoverEvidencePlanning.sample(3, "Short", "A copper automaton tends an orchard.")
        assertEquals("A copper automaton tends an orchard.", short!!.opening)
        assertEquals("", short.middle)
        assertNull(CoverEvidencePlanning.sample(4, "Blank", " \n "))
    }

    @Test fun `sampling handles chapters shorter than two full excerpts`() {
        for (length in listOf(2_200, 2_201, 2_746, 4_399, 4_400)) {
            val text = (0 until length).joinToString("") { (it % 10).toString() }
            val sample = CoverEvidencePlanning.sample(1, "Boundary", text)!!
            assertEquals(text.take(2_200), sample.opening)
            assertEquals(if (length <= 2_200) "" else text.substring(2_200), sample.middle)
            assertTrue(sample.middle.length <= 2_200)
        }
    }

    @Test fun `state carries both excerpts for long chapters and omits middle when absent`() {
        val story = Story(title = "Orchard", description = "A machine learns farming")
        val long = CoverEvidencePlanning.state(story, CoverEvidencePlanning.ChapterSample(2, "Two", "Opening text.", "Middle text."))
        assertEquals("Opening text.", long.get("opening").asString)
        assertEquals("Middle text.", long.get("middle").asString)
        val short = CoverEvidencePlanning.state(story, CoverEvidencePlanning.ChapterSample(2, "Two", "Opening text.", ""))
        assertFalse(short.has("middle"))
    }

    @Test fun `cache identity changes with evidence metadata model and questions but not credentials`() {
        val sample = CoverEvidencePlanning.ChapterSample(1, "Opening", "A copper automaton tends an orchard.", "The orchard hums at dusk.")
        val story = Story(title = "Orchard", description = "A machine learns farming")
        val body = TypeSafeCoverClient.request(CoverEvidencePlanning.state(story, sample))
        val original = CoverEvidencePlanning.cacheKey(body)
        assertEquals(original, CoverEvidencePlanning.cacheKey(body.deepCopy()))
        val changed =
            listOf(
                TypeSafeCoverClient.request(CoverEvidencePlanning.state(story.copy(description = "Changed premise"), sample)),
                TypeSafeCoverClient.request(CoverEvidencePlanning.state(story, sample.copy(opening = "New text"))),
                body.deepCopy().apply { addProperty("model", "new-model") },
                body.deepCopy().apply { getAsJsonObject("questions").remove("appearance") },
            )
        assertTrue(changed.all { CoverEvidencePlanning.cacheKey(it) != original })
        assertFalse(body.toString().contains("apiKey"))
    }

    @Test fun `useful chapters need visual evidence that is not a temporary scene`() {
        assertTrue(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.6, 0.0, 0.3, 0.0)))
        assertTrue(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.0, 0.0, 1.0, 0.5)))
        // Premise alone never qualifies and neither does a likely temporary scene.
        assertFalse(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.4, 1.0, 0.4, 0.0)))
        assertFalse(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(1.0, 0.0, 1.0, 0.6)))
        assertTrue(
            CoverEvidencePlanning.utility(CoverEvidencePlanning.Judgments(1.0, 1.0, 1.0, 0.0)) >
                CoverEvidencePlanning.utility(CoverEvidencePlanning.Judgments(1.0, 1.0, 1.0, 1.0)),
        )
    }

    @Test fun `choose keeps the strongest chapters within the target in reading order`() {
        fun scored(
            chapter: Int,
            evidence: Double,
            temporary: Double = 0.0,
        ) = CoverEvidencePlanning.ScoredChapter(
            CoverEvidencePlanning.ChapterSample(chapter, "Chapter $chapter", "Text $chapter", ""),
            CoverEvidencePlanning.Judgments(evidence, evidence, evidence, temporary),
        )
        val useful = (1..25).map { chapter -> scored(chapter, if (chapter % 2 == 0) 1.0 else 0.5) }
        val chosen = CoverEvidencePlanning.choose(useful, target = 10)
        assertEquals(10, chosen.size)
        assertEquals(chosen.map { it.sample.chapter }, chosen.map { it.sample.chapter }.sorted())
        assertEquals((2..20 step 2).toList(), chosen.map { it.sample.chapter })
        assertTrue(CoverEvidencePlanning.choose(emptyList(), target = 10).isEmpty())
    }
}
