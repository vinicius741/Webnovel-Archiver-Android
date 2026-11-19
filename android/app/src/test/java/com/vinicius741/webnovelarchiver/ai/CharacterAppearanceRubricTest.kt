package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAppearanceRubricTest {
    private val ivo = StoryCharacter("ivo", "Ivo", listOf("the apprentice"), CharacterRole.MAJOR, "", 0, null, null)

    private fun paragraph(
        index: Int,
        body: String = "Tide and brass.",
    ) = "Paragraph $index. " + body.repeat(90)

    @Test fun `a sample starts one passage before the character's first mention and fits the request limit`() {
        val text = (0 until 40).joinToString("\n") { if (it == 12) paragraph(it, "Ivo wore a grey scarf. ") else paragraph(it) }
        val rubric = CharacterAppearanceRubric(ivo, listOf(0))
        val sample = rubric.sample(7, "Chapter 7", text)!!
        val all = ChapterEvidencePlanning.passages(text, CoverEvidencePlanning.PASSAGE_CHARS)
        val mention = all.indexOfFirst { "Ivo" in it }
        assertEquals(all[mention - 1], sample.passages.first())
        assertTrue(sample.passages.any { "Ivo" in it })
        val body = rubric.request(Story(title = "T", description = "d".repeat(5_000)), sample, "typesafe/jev-1.14")
        assertTrue(body.toString().toByteArray().size <= CoverEvidencePlanning.MAX_REQUEST_BYTES)
        // The name travels as data, never inside a question.
        assertFalse(body.getAsJsonObject("questions").toString().contains("Ivo"))
        assertEquals(
            "Ivo",
            body
                .getAsJsonObject("state")
                .getAsJsonObject("character")
                .get("name")
                .asString,
        )
    }

    @Test fun `flagged passages make a chapter useful`() {
        val rubric = CharacterAppearanceRubric(ivo, listOf(0))
        val sample = ChapterEvidencePlanning.ChapterSample(3, "", "", "", listOf("a", "b"))

        fun answer(value: Double) =
            JsonObject().apply {
                addProperty("type", "noul")
                addProperty("noul", value)
            }
        val response =
            JsonObject().apply {
                add(
                    "answers",
                    JsonObject().apply {
                        add("looks_p0", answer(0.1))
                        add("looks_p1", answer(0.8))
                    },
                )
            }
        val assessment = rubric.assess(sample, response)
        assertTrue(assessment.useful)
        assertEquals(listOf("b"), assessment.passages.map { it.text })
        assertEquals(0.8, assessment.utility, 1e-9)
    }

    @Test fun `scan order maps key chapters to downloaded positions and skips missing ones`() {
        val downloaded = listOf(2, 3, 5, 8, 13)
        assertEquals(listOf(3, 0, 4), CharacterAppearanceRubric.order(downloaded, listOf(8, 2, 4, 13, 8)))
        assertEquals(listOf(1), CharacterAppearanceRubric(ivo, listOf(1, 9)).scanOrder(5))
    }
}
