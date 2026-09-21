package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverEvidencePlanningTest {
    @Test fun `scan covers the end of long chapters and preserves source positions`() {
        val text = (1..600).joinToString("\n\n") { "Paragraph $it contains an important description." }
        val passages = CoverEvidencePlanning.passages(17, "Later", text)
        assertTrue(passages.size > 3)
        assertTrue(passages.last().text.contains("Paragraph 600"))
        assertTrue(passages.all { it.chapter == 17 && it.text.length <= CoverEvidencePlanning.PASSAGE_CHARS })
        for (index in text.indices.filter { !text[it].isWhitespace() }) {
            assertTrue("Uncovered character $index", passages.any { index >= it.offset && index < it.offset + it.text.length })
        }
    }

    @Test fun `cache identity changes with evidence metadata model and questions but not credentials`() {
        val passage = CoverEvidencePlanning.Passage(1, "Opening", 0, "A copper automaton tends an orchard.")
        val story = Story(title = "Orchard", description = "A machine learns farming")
        val body = TypeSafeCoverClient.request(CoverEvidencePlanning.state(story, passage))
        val original = CoverEvidencePlanning.cacheKey(body)
        assertEquals(original, CoverEvidencePlanning.cacheKey(body.deepCopy()))
        val changed =
            listOf(
                TypeSafeCoverClient.request(CoverEvidencePlanning.state(story.copy(description = "Changed premise"), passage)),
                TypeSafeCoverClient.request(CoverEvidencePlanning.state(story, passage.copy(text = "New text"))),
                body.deepCopy().apply { addProperty("model", "new-model") },
                body.deepCopy().apply { getAsJsonObject("questions").remove("appearance") },
            )
        assertTrue(changed.all { CoverEvidencePlanning.cacheKey(it) != original })
        assertFalse(body.toString().contains("apiKey"))
    }

    @Test fun `selection covers complementary dimensions with a bounded prompt and chapter order`() {
        fun candidate(
            chapter: Int,
            text: String,
            a: Double,
            p: Double,
            i: Double,
        ) = CoverEvidencePlanning.Candidate(
            CoverEvidencePlanning.Passage(chapter, "Chapter $chapter", 0, text),
            CoverEvidencePlanning.Judgments(a, p, i, 0.0),
        )
        val character = candidate(20, "bronze automaton copper limbs orchard".repeat(80), 1.0, 0.0, 0.0)
        val premise = candidate(2, "farming family harvest friendship community".repeat(80), 0.0, 1.0, 0.0)
        val setting = candidate(31, "crystal valley mountains river moonlight".repeat(80), 0.0, 0.0, 1.0)
        val duplicates = (40..100).map { character.copy(passage = character.passage.copy(chapter = it)) }
        val selected = CoverEvidencePlanning.select(listOf(character, premise, setting) + duplicates)
        assertEquals(listOf(2, 20, 31), selected.map { it.number })
        assertTrue(selected.sumOf { it.text.length } <= CoverEvidencePlanning.CONTEXT_CHARS)
    }

    @Test fun `shortlist stays bounded while retaining different book phases`() {
        val candidates =
            (1..1000).map { chapter ->
                CoverEvidencePlanning.Candidate(
                    CoverEvidencePlanning.Passage(chapter, "", 0, "Chapter $chapter"),
                    CoverEvidencePlanning.Judgments(1.0, 0.5, 0.5, 0.0),
                )
            }
        val shortlist = CoverEvidencePlanning.shortlist(candidates, 1000)
        assertTrue(shortlist.size <= 144)
        assertEquals(setOf(0, 1, 2, 3), shortlist.map { (it.passage.chapter - 1) / 250 }.toSet())
        assertTrue(CoverEvidencePlanning.passages(1, "", " \n ").isEmpty())
        assertTrue(CoverEvidencePlanning.select(emptyList()).isEmpty())
    }
}
