package com.vinicius741.webnovelarchiver.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterMiningTest {
    private fun chapter(
        number: Int,
        text: String,
    ) = CharacterMining.ChapterText(number, text)

    private val book =
        listOf(
            chapter(1, "The tide rose. Mara lifted her clock while Ivo watched. \"Hold it,\" said Mara. The mana hummed."),
            chapter(2, "Later, Mara walked to the Harbor Library with Ivo. Ivo asked about the map. Mara's coat was blue."),
            chapter(3, "Then Mana flowed again. When Ivo slept, Mara read. Captain Rhys Hollow waved to Mara."),
            chapter(4, "Rhys Hollow returned. \"Wait,\" Ivo said. Rhys Hollow laughed while Mara counted the mana and mana."),
        )

    @Test fun `names that appear mid-sentence and speak are candidates, common words and lowercase terms are not`() {
        val candidates = CharacterMining.candidates(book)
        val names = candidates.map { it.name }
        assertTrue("Mara" in names)
        assertTrue("Ivo" in names)
        assertTrue("Rhys Hollow" in names)
        assertFalse("Then" in names)
        assertFalse("When" in names)
        // "Mana" is written in lowercase more often than capitalized: a term, not a name.
        assertFalse("Mana" in names)
        val mara = candidates.first { it.name == "Mara" }
        assertEquals(1, mara.firstChapter)
        assertEquals(4, mara.lastChapter)
        assertTrue(mara.speech >= 1)
        assertTrue(mara.snippets.isNotEmpty())
        assertTrue(mara.snippets.all { it.text.contains("Mara") && it.text.length <= CharacterMining.SNIPPET_CHARS })
        assertEquals("Mara", candidates.first().name)
    }

    @Test fun `mentions count every name and alias as whole words per chapter`() {
        val mentions = CharacterMining.mentions(book, listOf("Rhys Hollow", "Captain Rhys"))
        assertEquals(mapOf(3 to 1, 4 to 2), mentions.perChapter)
        assertEquals(3, mentions.count)
        assertEquals(3, mentions.first)
        assertEquals(4, mentions.last)
        // Whole words only: "Ivo" never matches inside another word.
        assertEquals(0, CharacterMining.mentions(listOf(chapter(1, "Ivory and Ivonne.")), listOf("Ivo")).count)
        assertNull(CharacterMining.mentions(book, emptyList()).first)
    }

    @Test fun `key chapters start with the entrance, then where the character is named most`() {
        val mentions = CharacterMining.Mentions(mapOf(9 to 1, 4 to 2, 20 to 7, 30 to 7, 12 to 3))
        assertEquals(listOf(4, 20, 30, 12, 9), CharacterMining.keyChapters(mentions, 10))
        assertEquals(listOf(4, 20), CharacterMining.keyChapters(mentions, 2))
        assertEquals(emptyList<Int>(), CharacterMining.keyChapters(CharacterMining.Mentions(emptyMap()), 5))
    }

    @Test fun `first mention finds the earliest of any name`() {
        assertEquals(4, CharacterMining.firstMention("The Ivo and Mara", listOf("Mara", "Ivo")))
        assertNull(CharacterMining.firstMention("Nobody here", listOf("Mara")))
    }
}
