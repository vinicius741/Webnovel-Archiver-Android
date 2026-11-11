package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroTextPlanningTest {
    private fun spoken(text: String) = KokoroTextPlanning.spokenText(text)

    @Test
    fun `closing marks after the final punctuation are dropped`() {
        // Each of these rendered about 0.2 s of noise cut off at full volume.
        assertEquals("“Perhaps.", spoken("“Perhaps.”"))
        assertEquals("“Why does it burn so much?", spoken("“Why does it burn so much?”"))
        assertEquals("‘Any progress, my lady?", spoken("‘Any progress, my lady?’"))
        assertEquals("\"I have not!", spoken("\"I have not!\""))
        assertEquals("You're acting strange.", spoken("You're acting strange.'"))
        assertEquals("[Skill acquired: Fireball.", spoken("[Skill acquired: Fireball.]"))
        assertEquals("『Return to the village.", spoken("『Return to the village.』"))
        assertEquals("He said, “Go home.", spoken("He said, “Go home.”"))
        assertEquals("“Is that so?!", spoken("“Is that so?!” "))
        assertEquals("He said, “Go home.", spoken("He said, “Go home”."))
    }

    @Test
    fun `spaced and separated terminators collapse into one run`() {
        assertEquals("Wait...", spoken("Wait. . ."))
        assertEquals("“Wait!.", spoken("“Wait!” ."))
    }

    @Test
    fun `asterisks are not spoken`() {
        assertEquals("Nobody moved.", spoken("*Nobody moved.*"))
        assertEquals("Thump The door shook.", spoken("*Thump* The door shook."))
    }

    @Test
    fun `text that already renders cleanly is unchanged`() {
        listOf(
            "Nobody moved.",
            "“Nobody moved”",
            "“Stop!” the guard shouted.",
            "“Go home.” He turned away.",
            "He waited…",
            "It cost 100%.",
            "It was 3.5 meters tall.",
            "[Skill acquired: Fireball]",
        ).forEach { assertEquals(it, spoken(it)) }
    }
}
