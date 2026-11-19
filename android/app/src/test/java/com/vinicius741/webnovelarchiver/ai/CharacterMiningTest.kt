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
        assertEquals(setOf("Mara", "Ivo"), candidates.take(2).map { it.name }.toSet())
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

    @Test fun `curly apostrophes, honorifics and possessive pairs are handled like plain text`() {
        val chapters =
            (1..4).map {
                chapter(
                    it,
                    "I’m tired. I’ll go. Then Mr. Darcy bowed and Mr. Darcy left while Jake’s Perception rose. " +
                        "Jake’s Perception grew. Jake’s Perception peaked. Unlike Darcy, unlike Jake.",
                )
            }
        val names = CharacterMining.candidates(chapters).map { it.name }
        assertFalse(names.any { it.startsWith("I'") })
        assertTrue("Darcy" in names)
        assertFalse(names.any { it.startsWith("Jake's") })
        assertFalse("Unlike Darcy" in names)
    }

    @Test fun `one roster-wide count gives each mention to the longest matching name`() {
        val counter = CharacterMining.MentionCounter(mapOf("li-mei" to listOf("Li Mei"), "li-qing" to listOf("Li Qing", "Li")))
        listOf(chapter(1, "Li Mei met Li Qing. Li’s sword flashed."), chapter(2, "Li Mei slept.")).forEach(counter::add)
        assertEquals(mapOf(1 to 1, 2 to 1), counter.result("li-mei").perChapter)
        assertEquals(mapOf(1 to 2), counter.result("li-qing").perChapter)
    }

    @Test fun `dialogue tags count as mid-sentence and a talking name that is also a word survives`() {
        val chapters =
            (1..3).map {
                chapter(
                    it,
                    "\"Hold on,\" Will said. \"Wait,\" Will said. \"Now,\" Will said. I will go and you will stay and we will see. " +
                        "Lord Vane bowed. Lord Vane left. \"No,\" Lin Feng said.",
                )
            }
        val names = CharacterMining.candidates(chapters).map { it.name }
        assertTrue("Will" in names)
        assertTrue("Lord Vane" in names)
        assertTrue(CharacterMining.candidates(chapters).first { it.name == "Lin Feng" }.speech >= 3)
    }
}
