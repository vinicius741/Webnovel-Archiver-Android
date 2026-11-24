package com.vinicius741.webnovelarchiver.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextChaptersTest {
    @Test
    fun recognizesHeadingsButNotProse() {
        assertEquals("Chapter 12: The Fall", LocalTextChapters.headingTitle("  Chapter 12: The Fall "))
        assertEquals("CHAPTER XIV", LocalTextChapters.headingTitle("CHAPTER XIV"))
        assertEquals("Chapter Twenty-One", LocalTextChapters.headingTitle("Chapter Twenty-One"))
        assertEquals("Prologue", LocalTextChapters.headingTitle("Prologue"))
        assertEquals("第12章 归来", LocalTextChapters.headingTitle("第12章 归来"))
        assertEquals("Intro", LocalTextChapters.headingTitle("## Intro", markdown = true))
        assertNull(LocalTextChapters.headingTitle("Chapter one of my life began in a small town by the sea, long ago."))
        assertNull(LocalTextChapters.headingTitle("Part of the plan was simple."))
        assertNull(LocalTextChapters.headingTitle("Partial victory"))
        assertNull(LocalTextChapters.headingTitle("Book it."))
        assertNull(LocalTextChapters.headingTitle("## Intro"))
    }

    @Test
    fun splitsAtHeadingsKeepingALongPreambleAndNamingVolumes() {
        val preamble = "A note before the story. ".repeat(12)
        val text =
            """
            $preamble

            Book One

            Chapter 1

            First line.

            Chapter 2 - Onward
            Second line.
            """.trimIndent()

        val chapters = LocalTextChapters.split(text)

        assertEquals(listOf(null, "Book One: Chapter 1", "Chapter 2 - Onward"), chapters.map { it.title })
        assertEquals("First line.", chapters[1].body)
        assertEquals("Second line.", chapters[2].body)
        assertEquals(3, chapters.map { it.key }.distinct().size)
    }

    @Test
    fun aRepeatedRunningHeadingOpensOneChapter() {
        val text = "Chapter 1\nOne.\nChapter 1\nStill one.\nChapter 2\nTwo."

        val chapters = LocalTextChapters.split(text)

        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals("One.\nStill one.", chapters[0].body)
    }

    @Test
    fun aBookWithoutHeadingsIsOneChapterOrSeveralParts() {
        val short = LocalTextChapters.split("Chapter 7\n\nJust one chapter here.")
        assertEquals(listOf("Chapter 7"), short.map { it.title })
        assertEquals("all", short.single().key)

        val paragraph = "word ".repeat(1_000).trim()
        val long = LocalTextChapters.split(List(15) { paragraph }.joinToString("\n\n"))
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), long.map { it.title })
        assertTrue(long.all { LocalTextChapters.wordCount(it.body) <= LocalTextChapters.PART_WORDS })
    }

    @Test
    fun paragraphsJoinHardWrapsOrFollowLines() {
        assertEquals(listOf("One two three.", "Four."), LocalTextChapters.paragraphs("One two\nthree.\n\nFour."))
        assertEquals(listOf("One.", "Two."), LocalTextChapters.paragraphs("One.\nTwo."))
        assertEquals("<p>A &lt;b&gt; &amp; c</p>", LocalTextChapters.toHtml("A <b> & c"))
        assertEquals(
            "<h3>Title</h3>\n<p>Some <strong>bold</strong> and <em>soft</em> text.</p>",
            LocalTextChapters.toHtml("## Title\n\nSome **bold** and *soft* text.", markdown = true),
        )
    }

    @Test
    fun decodesUtf8AndOldWindowsText() {
        assertEquals("café", LocalTextChapters.decode("café".toByteArray(Charsets.UTF_8)))
        assertEquals("café", LocalTextChapters.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
        assertEquals("hi", LocalTextChapters.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x68, 0x69)))
        assertEquals("02 The Return", LocalTextChapters.titleFromFileName("02_The_Return.txt"))
    }

    @Test
    fun hardWrappedProseIsNotAHeading() {
        listOf(
            "part I could not understand, and so I went back to the house",
            "book I had been reading all night fell from my hands and",
            "Chapter 3 of the manual says you should never do this",
            "Introduction to the mission was brief and we left at dawn",
            "Part I was supposed to play in this",
            "Chapter 1 ........ 5",
        ).forEach { assertNull(it, LocalTextChapters.headingTitle(it)) }
        assertEquals("Part IV: The Return", LocalTextChapters.headingTitle("Part IV: The Return"))
        assertEquals("Chapter 3 The Fall of Rome", LocalTextChapters.headingTitle("Chapter 3 The Fall of Rome"))
    }

    @Test
    fun chapterKeysFollowTitlesSoAnInsertedChapterMovesNoOther() {
        val before = LocalTextChapters.split((1..3).joinToString("\n") { "Chapter $it\nText $it." })
        val after =
            LocalTextChapters.split(
                "Chapter 1\nText 1.\nInterlude\nBetween.\nChapter 2\nText 2.\nChapter 3\nText 3.\nChapter 3\n\nChapter 3\nAgain.",
            )

        val keysBefore = before.associate { it.title to it.key }
        after.filter { it.title in keysBefore }.forEach { assertEquals(keysBefore[it.title], it.key) }
        assertEquals("interlude", after[1].key)
        assertEquals(after.size, after.map { it.key }.distinct().size)
    }

    @Test
    fun aContentsPageAndAnEmptyFileMakeNoChapters() {
        val text = "Contents\nChapter 1\nChapter 2\n\nChapter 1\nOne.\nChapter 2\nTwo."

        assertEquals(listOf("Chapter 1", "Chapter 2"), LocalTextChapters.split(text).map { it.title })
        assertEquals(emptyList<TextChapter>(), LocalTextChapters.split(""))
        assertEquals(emptyList<TextChapter>(), LocalTextChapters.split("Chapter 1\n\n"))
    }
}
