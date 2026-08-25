package com.vinicius741.webnovelarchiver.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChapterBlockParsingTest {
    @Test
    fun `sanitizer strips scripts styles and attributes but keeps text and inline tags`() {
        val dirty =
            "<div style=\"x:1\" onclick=\"evil()\"><script>bad()</script>" +
                "<p class=\"junk\" style=\"m:1\">keep <b>bold</b> &amp; <i>it</i></p>" +
                "<iframe src=\"x\"></iframe><span style=\"font-weight:400\">tail</span></div>"
        val clean = ChapterBlockParsing.sanitizeChapterHtml(dirty)
        assertFalse(clean.contains("script"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("iframe"))
        assertTrue(clean.contains("keep"))
        assertTrue(clean.contains("<b>bold</b>"))
        assertTrue(clean.contains("tail"))
    }

    @Test
    fun `sanitizer escapes raw text so round trips stay parseable`() {
        val clean = ChapterBlockParsing.sanitizeChapterHtml("<p>5 &lt; 6 &amp; 7 > 2</p>")
        assertTrue(clean.contains("5 &lt; 6 &amp; 7 &gt; 2"))
        val parsed = ChapterBlockParsing.parseChapter(clean)
        assertEquals(1, parsed.blocks.size)
        assertEquals("5 < 6 & 7 > 2", ChapterBlockParsing.textOf(parsed.blocks[0].html))
    }

    @Test
    fun `textOf decodes entities and maps br to newlines`() {
        assertEquals("a\nb & c", ChapterBlockParsing.textOf("<p>a<br>b &amp; c</p>"))
    }

    @Test
    fun `synthetic litrpg chapter protects tables blockquotes dividers headings and spacers`() {
        val chapter = ChapterBlockParsing.parseChapter(fixture("synthetic_litrpg_ch1.html"))
        val protected = chapter.blocks.filter { it.protected }
        val tags = protected.map { it.tag }.toSet()
        assertTrue(tags.containsAll(setOf("table", "blockquote", "hr", "h3")))
        assertTrue(protected.any { it.reason == "spacer" })
        assertTrue(chapter.blocks.any { !it.protected && it.html.contains("\"") })
        assertEquals(
            chapter.blocks.map { it.id },
            (1..chapter.blocks.size).map { ChapterBlockParsing.blockId(it - 1) },
        )
        protected.forEach { block ->
            assertEquals(ChapterBlockParsing.protectedHash(block.html), block.protectedHash)
        }
    }

    @Test
    fun `stat-like text is protected and prose is not`() {
        // Classification is line-ratio based: at least half the lines must be stat/label-shaped.
        val stat = "<p>[Quest Complete]<br>You have gained 250 XP.</p>"
        val numericPanel = "<p>[HP 10/10]<br>10/10<br>You feel stronger.</p>"
        val prose = "<p>She walked into the room and looked around slowly.</p>"
        assertTrue(ChapterBlockClassification.classify("p", stat, ChapterBlockParsing.textOf(stat)).first)
        assertTrue(ChapterBlockClassification.classify("p", numericPanel, ChapterBlockParsing.textOf(numericPanel)).first)
        assertFalse(ChapterBlockClassification.classify("p", prose, ChapterBlockParsing.textOf(prose)).first)
    }

    @Test
    fun `loose top-level content becomes a protected pre-block`() {
        val parsed = ChapterBlockParsing.parseChapter("stray intro text<p>One.</p>")
        assertEquals(2, parsed.blocks.size)
        assertEquals("b0001", parsed.blocks[0].id)
        assertEquals("pre", parsed.blocks[0].tag)
        assertTrue(parsed.blocks[0].protected)
        assertEquals("loose content outside block tags", parsed.blocks[0].reason)
        assertEquals("b0002", parsed.blocks[1].id)
    }

    @Test
    fun `mid and trailing loose content stay at their document position`() {
        // Regression guard: stray content found between or after paragraphs must not be hoisted
        // to the chapter's head — the polished output keeps the original reading order.
        val parsed = ChapterBlockParsing.parseChapter("<p>One.</p><br>between<p>Two.</p>trailing note")
        assertEquals(listOf("p", "pre", "p", "pre"), parsed.blocks.map { it.tag })
        assertEquals("<br>between", parsed.blocks[1].html)
        assertEquals("trailing note", parsed.blocks[3].html)
        assertEquals(listOf("b0001", "b0002", "b0003", "b0004"), parsed.blocks.map { it.id })
    }

    @Test
    fun `source hash is stable across formatting whitespace`() {
        val a = ChapterBlockParsing.parseChapter("<p>One.</p>\n\n<p>Two.</p>")
        val b = ChapterBlockParsing.parseChapter("<p>One.</p>  <p>Two.</p>")
        assertEquals(a.sourceSha256, b.sourceSha256)
        val c = ChapterBlockParsing.parseChapter("<p>One.</p><p>Two two.</p>")
        assertNotEquals(a.sourceSha256, c.sourceSha256)
    }

    @Test
    fun `normalizeForCompare is whitespace case insensitive but content sensitive`() {
        // Interior whitespace runs collapse and casing drops; inter-tag whitespace disappears.
        assertEquals(
            ChapterBlockParsing.normalizeForCompare("<p>A  B</p>"),
            ChapterBlockParsing.normalizeForCompare("<p>a b</p>"),
        )
        assertEquals(
            ChapterBlockParsing.normalizeForCompare("<blockquote>\n   <strong>x</strong>\n</blockquote>"),
            ChapterBlockParsing.normalizeForCompare("<blockquote><strong>x</strong></blockquote>"),
        )
        assertNotEquals(
            ChapterBlockParsing.normalizeForCompare("<p>a</p>"),
            ChapterBlockParsing.normalizeForCompare("<p>b</p>"),
        )
    }

    @Test
    fun `output sanitizer strips hazard tags attributes and notes them`() {
        val (clean, notes) = ChapterBlockParsing.sanitizeOutputBlock("<p onclick=\"x()\">Alpha <img src=\"http://x\">sentence.</p>")
        assertTrue(notes.contains("removed <img>"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("img"))
        assertTrue(clean.contains("Alpha"))
        assertTrue(clean.startsWith("<p>"))
    }

    @Test
    fun `output sanitizer keeps only prose allowlist tags`() {
        val (clean, _) = ChapterBlockParsing.sanitizeOutputBlock("<table><tr><td>x</td></tr></table><p>ok <u>u</u> <em>e</em></p>")
        assertFalse(clean.contains("table"))
        assertFalse(clean.contains("<u>"))
        assertTrue(clean.contains("x"))
        assertTrue(clean.contains("<em>e</em>"))
    }

    @Test
    fun `assembled html drops merged empty blocks`() {
        val blocks =
            listOf(
                ChapterBlock("b0001", "p", "<p>Carrier.</p>", false, "rewritten"),
                ChapterBlock("b0002", "p", "", false, "merged"),
            )
        assertEquals("<p>Carrier.</p>", ChapterBlockParsing.assembleChapterHtml(blocks.filter { it.html.isNotEmpty() }))
    }

    @Test
    fun `foxkin reference chapter parses within spike tolerances`() {
        // The foxkin chapter is a Royal Road author's copyrighted prose. Like the spike's
        // corpus/local/ copy, the fixture is gitignored and never published; clones without it
        // skip the reference check instead of failing.
        val html = optionalFixture("foxkin_ch1.html")
        assumeTrue("local-only foxkin fixture absent", html != null)
        val chapter = ChapterBlockParsing.parseChapter(html!!)
        val report = ChapterCadenceReport.cadenceOf(chapter.blocks)
        // Spike measured 186 paragraphs / 94 fragments / 10 clusters (plan's hand count: 187/91/11).
        assertTrue("paragraphs=${report.paragraphCount}", report.paragraphCount in 180..195)
        assertTrue("fragments=${report.fragmentParagraphs}", report.fragmentParagraphs in 84..98)
        assertTrue("clusters=${report.clusterCount}", report.clusterCount in 7..15)
        assertTrue(chapter.sourceSha256.isNotBlank())
        assertNull(chapter.blocks.lastOrNull { it.tag == "pre" && it.reason != "loose content outside block tags" })
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/chapterpolish/$name")!!.bufferedReader().use { it.readText() }

    private fun optionalFixture(name: String): String? =
        javaClass.getResourceAsStream("/fixtures/chapterpolish/$name")?.bufferedReader()?.use { it.readText() }
}
