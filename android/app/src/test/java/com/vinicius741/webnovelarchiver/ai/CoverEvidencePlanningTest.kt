package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverEvidencePlanningTest {
    private companion object {
        const val MODEL = "typesafe/jev-1.14"
    }

    @Test fun `scan order reads the opening chapters in order then spreads breadth first across the book`() {
        assertEquals(emptyList<Int>(), CoverEvidencePlanning.scanOrder(0))
        assertEquals(listOf(0), CoverEvidencePlanning.scanOrder(1))
        assertEquals((0 until 10).toList(), CoverEvidencePlanning.scanOrder(10))
        assertEquals((0 until 10).toList() + listOf(10, 12, 11, 13), CoverEvidencePlanning.scanOrder(14))
        val order = CoverEvidencePlanning.scanOrder(40)
        assertEquals((0 until 40).toList(), order.sorted())
        assertEquals((0 until 10).toList() + ChapterEvidencePlanning.spreadOrder(30).map { it + 10 }, order)
        assertEquals(listOf(10, 25, 18, 33), order.subList(10, 14))
    }

    @Test fun `passages pack whole lines up to the size cap and hard split an oversized line`() {
        val text = "First line.\nSecond line.\n\n  \nThird line that is longer."
        assertEquals(listOf("First line.\nSecond line.", "Third line that is longer."), ChapterEvidencePlanning.passages(text, 26))
        assertEquals(listOf("abcde", "fghij", "k"), ChapterEvidencePlanning.passages("abcdefghijk", 5))
        assertTrue(ChapterEvidencePlanning.passages(" \n\n ", 100).isEmpty())
    }

    @Test fun `cover sample sends the whole chapter as passages until the byte budget is spent`() {
        val paragraphs = (1..40).map { "Paragraph $it ".padEnd(700, 'x') }
        val short = CoverEvidencePlanning.sample(3, "Short", paragraphs.take(4).joinToString("\n"))!!
        assertEquals("", short.opening)
        assertEquals(paragraphs.take(4).joinToString("\n"), short.passages.joinToString("\n"))
        assertTrue(short.passages.all { it.length <= CoverEvidencePlanning.PASSAGE_CHARS })
        val long = CoverEvidencePlanning.sample(9, "Long", paragraphs.joinToString("\n"))!!
        assertTrue(long.passages.first().startsWith("Paragraph 1 "))
        assertTrue(long.passages.size in 2 until 20)
        assertNull(CoverEvidencePlanning.sample(4, "Blank", " \n "))
    }

    @Test fun `a worst case cover request stays under the long context switch`() {
        // Multi-byte text, maximal metadata and the longest chapter the budget admits.
        val story =
            Story(
                title = "Ð".repeat(600),
                description = "É".repeat(5_000),
                tags = MutableList(200) { "tag-é-$it" },
            )
        val text = (1..400).joinToString("\n") { "“Quoted” line $it with ünïcödé \\ and \"escapes\" ".repeat(12) }
        val sample = CoverEvidencePlanning.sample(12, "T".repeat(500), text)!!
        val body = CoverEvidencePlanning.request(story, sample, MODEL)
        assertTrue(body.toString().toByteArray().size <= CoverEvidencePlanning.MAX_REQUEST_BYTES)
        assertEquals(sample.passages.size, body.getAsJsonObject("questions").keySet().count { it.startsWith("appearance_p") })
    }

    @Test fun `assessment keeps passages at or above the threshold and requires every passage answer`() {
        val sample = ChapterEvidencePlanning.ChapterSample(5, "Five", "", "", listOf("Grey eyes.", "The market.", "Tall and lean."))
        val answers =
            """{"answers":{
              "appearance":{"type":"score","score":3},"premise":{"type":"score","score":1},
              "imagery":{"type":"score","score":2},"temporary":{"type":"noul","noul":0},
              "appearance_p0":{"type":"noul","noul":0.92},"appearance_p1":{"type":"noul","noul":0.1},
              "appearance_p2":{"type":"noul","noul":0.5}}}"""
        val assessment =
            CoverEvidencePlanning.assess(
                sample,
                com.google.gson.JsonParser
                    .parseString(answers)
                    .asJsonObject,
            )
        assertTrue(assessment.useful)
        assertEquals(listOf(0 to "Grey eyes.", 2 to "Tall and lean."), assessment.passages.map { it.index to it.text })
        assertTrue(assessment.passages.all { it.chapter == 5 })
        val missing =
            com.google.gson.JsonParser
                .parseString(answers.replace("\"appearance_p2\"", "\"other\""))
                .asJsonObject
        assertTrue(runCatching { CoverEvidencePlanning.assess(sample, missing) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `sampling takes the opening and a mid chapter window and skips blank text`() {
        val text = (1..600).joinToString("\n\n") { "Paragraph $it contains an important description." }
        val sample = ChapterEvidencePlanning.sample(17, "Later", text)!!
        assertTrue(sample.opening.contains("Paragraph 1"))
        assertTrue(sample.middle.isNotEmpty())
        assertTrue(sample.opening.length <= 2_200 && sample.middle.length <= 2_200)
        val short = ChapterEvidencePlanning.sample(3, "Short", "A copper automaton tends an orchard.")
        assertEquals("A copper automaton tends an orchard.", short!!.opening)
        assertEquals("", short.middle)
        assertNull(ChapterEvidencePlanning.sample(4, "Blank", " \n "))
    }

    @Test fun `sampling handles chapters shorter than two full excerpts`() {
        for (length in listOf(2_200, 2_201, 2_746, 4_399, 4_400)) {
            val text = (0 until length).joinToString("") { (it % 10).toString() }
            val sample = ChapterEvidencePlanning.sample(1, "Boundary", text)!!
            assertEquals(text.take(2_200), sample.opening)
            assertEquals(if (length <= 2_200) "" else text.substring(2_200), sample.middle)
            assertTrue(sample.middle.length <= 2_200)
        }
    }

    @Test fun `state nests novel background and lists passages by index`() {
        val story = Story(title = "Orchard", description = "A machine learns farming", tags = mutableListOf("robots"))
        val state = CoverEvidencePlanning.state(story, ChapterEvidencePlanning.ChapterSample(2, "Two", "", "", listOf("One.", "Two.")))
        assertEquals("Orchard", state.getAsJsonObject("novel").get("title").asString)
        assertEquals("A machine learns farming", state.getAsJsonObject("novel").get("description").asString)
        assertEquals(2, state.get("chapter_position").asInt)
        assertEquals(listOf("One.", "Two."), state.getAsJsonArray("passages").map { it.asString })
        val question = CoverEvidencePlanning.request(state, 2, MODEL).getAsJsonObject("questions").getAsJsonObject("appearance_p1")
        assertTrue(question.get("instructions").asString.startsWith("Does `passages[1]` describe how the novel's main character looks"))
    }

    @Test fun `cache identity changes with evidence metadata model and questions but not credentials`() {
        val sample =
            ChapterEvidencePlanning.ChapterSample(
                1,
                "Opening",
                "",
                "",
                listOf("A copper automaton tends an orchard.", "The orchard hums at dusk."),
            )
        val story = Story(title = "Orchard", description = "A machine learns farming")
        val body = CoverEvidencePlanning.request(story, sample, MODEL)
        val original = ChapterEvidencePlanning.cacheKey(body)
        assertEquals(original, ChapterEvidencePlanning.cacheKey(body.deepCopy()))
        val changed =
            listOf(
                CoverEvidencePlanning.request(story.copy(description = "Changed premise"), sample, MODEL),
                CoverEvidencePlanning.request(story, sample.copy(passages = listOf("New text")), MODEL),
                body.deepCopy().apply { addProperty("model", "new-model") },
                body.deepCopy().apply { getAsJsonObject("questions").remove("appearance") },
            )
        assertTrue(changed.all { ChapterEvidencePlanning.cacheKey(it) != original })
        assertFalse(body.toString().contains("apiKey"))
    }

    @Test fun `useful chapters need visual evidence that is not a temporary scene`() {
        assertTrue(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.7, 0.0, 0.3, 0.0)))
        assertTrue(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.0, 0.0, 1.0, 0.5)))
        // Generic mentions (level 1 of 3), premise alone, and likely temporary scenes never qualify.
        assertFalse(CoverEvidencePlanning.isUseful(CoverEvidencePlanning.Judgments(0.6, 0.0, 0.6, 0.0)))
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
        ) = ChapterEvidencePlanning.ScoredChapter(
            ChapterEvidencePlanning.ChapterSample(chapter, "Chapter $chapter", "Text $chapter", ""),
            ChapterEvidencePlanning.Assessment(
                useful = true,
                utility = CoverEvidencePlanning.utility(CoverEvidencePlanning.Judgments(evidence, evidence, evidence, temporary)),
            ),
        )
        val useful = (1..25).map { chapter -> scored(chapter, if (chapter % 2 == 0) 1.0 else 0.5) }
        val chosen = ChapterEvidencePlanning.choose(useful, target = 10)
        assertEquals(10, chosen.size)
        assertEquals(chosen.map { it.sample.chapter }, chosen.map { it.sample.chapter }.sorted())
        assertEquals((2..20 step 2).toList(), chosen.map { it.sample.chapter })
        assertTrue(ChapterEvidencePlanning.choose(emptyList(), target = 10).isEmpty())
    }
}
