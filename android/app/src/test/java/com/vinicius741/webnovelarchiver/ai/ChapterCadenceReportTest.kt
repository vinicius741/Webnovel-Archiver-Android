package com.vinicius741.webnovelarchiver.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChapterCadenceReportTest {
    @Test
    fun `sentence splitting keeps abbreviations together and counts fragment triplets`() {
        val sentences =
            ChapterCadenceReport.splitSentences(
                "Mr. Bennet was so odd. “One out of ten.” A pause. A calculation. A prediction.",
            )
        assertTrue(sentences.any { it.startsWith("Mr. Bennet") })
        assertEquals(
            3,
            sentences.count { it.trim() in setOf("A pause.", "A calculation.", "A prediction.") },
        )
    }

    @Test
    fun `fragment clusters are runs of three or more short paragraphs`() {
        val blocks =
            (1..2).map { index ->
                ChapterBlock(
                    "b%04d".format(index),
                    "p",
                    "<p>The long sentence that carries the scene forward with detail.</p>",
                    false,
                    "prose",
                )
            } +
                (1..4).map { index -> ChapterBlock("b1%03d".format(index), "p", "<p>Beat $index.</p>", false, "prose") }
        val report = ChapterCadenceReport.cadenceOf(blocks)
        assertEquals(1, report.clusterCount)
        assertEquals(4, report.clusterParagraphs)
        assertEquals(4, report.fragmentParagraphs)
    }

    @Test
    fun `protected blocks are excluded from cadence`() {
        val blocks =
            listOf(
                ChapterBlock("b0001", "p", "<p>Short one.</p>", false, "prose"),
                ChapterBlock("b0002", "p", "<p>Short two.</p>", false, "prose"),
                ChapterBlock("b0003", "p", "<p>Short three.</p>", false, "prose"),
                ChapterBlock("b0004", "blockquote", "<blockquote>[SYSTEM] Level 4</blockquote>", true, "panel"),
            )
        val report = ChapterCadenceReport.cadenceOf(blocks)
        assertEquals(3, report.paragraphCount)
        assertEquals(1, report.clusterCount)
    }

    @Test
    fun `em dash density counts per thousand words`() {
        // Dashes attached to words count once each and do not add word tokens.
        val blocks = listOf(ChapterBlock("b0001", "p", "<p>a— b— c— d— e</p>", false, "prose"))
        val report = ChapterCadenceReport.cadenceOf(blocks)
        assertEquals(5, report.wordCount)
        assertEquals(800.0, report.emDashDensityPer1000Words, 0.01)
    }

    @Test
    fun `template swap is flagged when one sentence shape dominates after fragment reduction`() {
        val beforeBlocks =
            listOf(
                "A pause.",
                "A calculation.",
                "A prediction.",
                "The long sentence that carries the scene forward with detail and movement goes here.",
                "Short again.",
                "Another one.",
                "Third short.",
                "End.",
            ).mapIndexed {
                index,
                text,
                ->
                ChapterBlock("b%04d".format(index + 1), "p", "<p>$text</p>", false, "prose")
            }
        val afterBlocks =
            (1..8).map { index ->
                ChapterBlock(
                    "b%04d".format(index),
                    "p",
                    "<p>The sentences here are all of one medium uniform length throughout.</p>",
                    false,
                    "prose",
                )
            }
        val comparison =
            ChapterCadenceReport.compare(
                ChapterCadenceReport.cadenceOf(beforeBlocks),
                ChapterCadenceReport.cadenceOf(afterBlocks),
            )
        assertTrue(comparison.templateSwapWarning)
        assertTrue(comparison.templateSwapDetail.contains("swapped one template rhythm for another"))
    }

    @Test
    fun `no template swap when rhythm genuinely varies`() {
        val beforeBlocks =
            listOf("A pause.", "A calculation.", "A prediction.", "Short again.", "Another one.", "Third short.").mapIndexed {
                index,
                text,
                ->
                ChapterBlock("b%04d".format(index + 1), "p", "<p>$text</p>", false, "prose")
            }
        val afterTexts =
            listOf(
                "One medium sentence with detail follows here.",
                "Short.",
                "A considerably longer sentence that unspools across several clauses before it lands at last.",
                "Medium again with movement.",
                "Punch.",
                "Another medium sentence carrying the beat forward.",
            )
        val afterBlocks =
            afterTexts.mapIndexed {
                index,
                text,
                ->
                ChapterBlock("b%04d".format(index + 1), "p", "<p>$text</p>", false, "prose")
            }
        val comparison =
            ChapterCadenceReport.compare(
                ChapterCadenceReport.cadenceOf(beforeBlocks),
                ChapterCadenceReport.cadenceOf(afterBlocks),
            )
        assertFalse(comparison.templateSwapWarning)
    }

    @Test
    fun `word counting splits on any whitespace`() {
        assertEquals(listOf("a", "b", "c"), ChapterCadenceReport.words("  a\tb\n c "))
    }

    @Test
    fun `foxkin cadence reproduces the spike reference numbers`() {
        // Copyrighted reference chapter, gitignored like the spike's corpus/local/ copy; clones
        // without it skip this cross-language port check instead of failing.
        val html = javaClass.getResourceAsStream("/fixtures/chapterpolish/foxkin_ch1.html")?.bufferedReader()?.use { it.readText() }
        assumeTrue("local-only foxkin fixture absent", html != null)
        val report = ChapterCadenceReport.cadenceOf(ChapterBlockParsing.parseChapter(html!!).blocks)
        // Spike measured 186/94/10; ±2 tolerance for the cross-language port.
        assertEquals(186.0, report.paragraphCount.toDouble(), 2.0)
        assertEquals(94.0, report.fragmentParagraphs.toDouble(), 2.0)
        assertEquals(10.0, report.clusterCount.toDouble(), 2.0)
    }
}
