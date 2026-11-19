package com.vinicius741.webnovelarchiver.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAppearanceScoutTest {
    private fun chapter(
        number: Int,
        text: String,
    ) = CharacterMining.ChapterText(number, text)

    @Test fun `the chapter that describes a character beats chapters that only name them often`() {
        val scout = CharacterAppearanceScout(listOf("Victoria"))
        scout.add(chapter(42, "Like Victoria, her abilities were rare."))
        scout.add(chapter(150, "Victoria ran. Victoria laughed. Victoria shouted. Victoria sat. Victoria slept. Victoria won."))
        scout.add(
            chapter(
                149,
                "Victoria was familiar enough that I did not mind.\nSlim, tall and with sharp cheekbones, she was the Vornholt look. " +
                    "Those golden blonde strands and bright blue eyes were classic Yilear.",
            ),
        )
        scout.add(chapter(10, "Nobody here."))
        val spots = scout.best(16)
        assertEquals(listOf(149, 150, 42), spots.map { it.chapter })
        assertTrue(spots.first().anchor.startsWith("Victoria was familiar"))
        assertEquals(6, spots[1].mentions)
    }

    @Test fun `the request starts just before the best-described mention`() {
        val filler = (0 until 30).joinToString("\n") { "Paragraph $it. " + "Tide and brass. ".repeat(80) }
        val text = "Victoria waved.\n$filler\nVictoria had golden blonde hair and bright blue eyes.\n$filler"
        val character =
            com.vinicius741.webnovelarchiver.domain.model.StoryCharacter(
                "victoria",
                "Victoria",
                emptyList(),
                com.vinicius741.webnovelarchiver.domain.model.CharacterRole.MAJOR,
                "",
                0,
                null,
                null,
            )
        val rubric = CharacterAppearanceRubric(character, listOf(0), mapOf(5 to "Victoria had golden blonde hair"))
        val sample = rubric.sample(5, "Ch 5", text)!!
        assertTrue(sample.passages.any { "golden blonde" in it })
        assertTrue(sample.passages.none { it.startsWith("Victoria waved") })
    }
}
