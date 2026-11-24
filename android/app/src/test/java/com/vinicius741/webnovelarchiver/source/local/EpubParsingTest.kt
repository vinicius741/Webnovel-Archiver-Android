package com.vinicius741.webnovelarchiver.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParsingTest {
    private val book = EpubParsing.readPackage(EpubFixtures::read)

    private fun plans() = EpubParsing.planChapters(book) { path -> EpubParsing.docInfo(EpubFixtures.entries.getValue(path)) }

    private fun html(index: Int) = EpubParsing.chapterHtml(plans()[index]) { EpubFixtures.entries[it] }

    @Test
    fun readsMetadataSpineTocAndCover() {
        assertEquals("The Small Book", book.metadata.title)
        assertEquals("A. Writer", book.metadata.author)
        assertEquals("A tale.", book.metadata.description)
        assertEquals(listOf("Fantasy"), book.metadata.subjects)
        assertEquals("OEBPS/images/cover.png", book.coverPath)
        assertEquals("OEBPS/text/ch 12.xhtml", book.spine[2])
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), book.toc.map { it.title })
    }

    @Test
    fun plansChaptersFromTheTableOfContents() {
        val plans = plans()

        // The cover and the short title page come before the first entry and are no chapters.
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), plans.map { it.title })
        assertEquals(listOf(EpubSegment("OEBPS/text/ch 12.xhtml", null, "two")), plans[0].segments)
        assertEquals("OEBPS/text/ch 12.xhtml#two", plans[1].key)
        assertEquals(listOf("OEBPS/text/ch3a.xhtml", "OEBPS/text/ch3b.xhtml"), plans[2].segments.map { it.path })
    }

    @Test
    fun chapterHtmlIsCutAtAnchorsAndCleaned() {
        val first = html(0)
        assertTrue(first.contains("One begins."))
        assertFalse(first.contains("Two goes on"))
        assertFalse(first.contains("<a"))
        assertFalse(first.contains("<p></p>"))
        assertFalse(first.contains("alert"))

        val second = html(1)
        assertTrue(second.contains("Two goes on."))
        assertFalse(second.contains("One begins"))

        val third = html(2)
        assertTrue(third.contains("first half") && third.contains("second half"))
        assertFalse(third.contains("<img"))
    }

    @Test
    fun withoutATableOfContentsEveryTextDocumentIsAChapter() {
        val plans =
            EpubParsing.planChapters(book.copy(toc = emptyList())) { path -> EpubParsing.docInfo(EpubFixtures.entries.getValue(path)) }

        assertEquals(listOf("The Small Book", "Chapter 1", "Chapter 3", "Chapter 4"), plans.map { it.title })
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/images/a b.png", EpubParsing.resolve("OEBPS/text", "../images/a%20b.png#x"))
        assertEquals("c.xhtml", EpubParsing.resolve("", "./c.xhtml"))
    }

    @Test
    fun anAnchorAtTheTopOfTheNextFileEndsThePreviousChapterThere() {
        val next = "<html><body><h1 id=\"c2\">Chapter 2</h1><p>Two.</p></body></html>"

        assertEquals("", EpubParsing.segmentHtml(next, EpubSegment("ch2.xhtml", null, "c2")))
        assertEquals("", EpubParsing.segmentHtml("<html><body id=\"c2\"><p>Two.</p></body></html>", EpubSegment("ch2.xhtml", null, "c2")))
        assertTrue(EpubParsing.segmentHtml(next, EpubSegment("ch2.xhtml", "c2", null)).contains("Two."))
    }

    @Test
    fun nestedAnchoredSectionsSplitAtEachAnchor() {
        val html =
            "<html><body><section id=\"part1\"><h1>Part 1</h1><p>Opening.</p>" +
                "<section id=\"ch1\"><h2>Chapter 1</h2><p>One.</p></section></section></body></html>"

        val part = EpubParsing.segmentHtml(html, EpubSegment("p.xhtml", "part1", "ch1"))
        val chapter = EpubParsing.segmentHtml(html, EpubSegment("p.xhtml", "ch1", null))

        assertTrue(part.contains("Opening.") && !part.contains("One."))
        assertTrue(chapter.contains("One.") && !chapter.contains("Opening."))
    }
}
