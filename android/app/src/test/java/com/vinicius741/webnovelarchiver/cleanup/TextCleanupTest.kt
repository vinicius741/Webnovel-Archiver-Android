package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.ui.size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanupTest {
    @Test
    fun cleanupAppliesToNestedTextNodesAndSkipsScripts() {
        val html =
            """
            <div>
              <p>Hello <strong>Support me on Patreon</strong> world</p>
              <p>Keep --- this marker</p>
              <script>Support me on Patreon</script>
            </div>
            """.trimIndent()

        val cleaned =
            TextCleanup.applyDownloadCleanup(
                html,
                listOf("Support me on Patreon"),
                listOf(
                    RegexCleanupRule(
                        id = "rule_1",
                        name = "marker",
                        pattern = "---",
                        flags = "g",
                        enabled = true,
                        appliesTo = "download",
                    ),
                ),
            )

        assertFalse(cleaned.contains("Support me on Patreon"))
        assertFalse(cleaned.contains("---"))
        assertFalse(cleaned.contains("<script"))
        assertTrue(cleaned.contains("Hello"))
    }

    @Test
    fun prepareTtsChunksAppliesTtsSpecificCleanupRules() {
        val html =
            """
            <body>
              <p>Keep this display-only phrase.</p>
              <p>Remove this spoken note.</p>
            </body>
            """.trimIndent()

        val chunks =
            TextCleanup.prepareTtsChunks(
                html,
                listOf(
                    RegexCleanupRule(
                        id = "display",
                        name = "display",
                        pattern = "display-only",
                        flags = "g",
                        enabled = true,
                        appliesTo = "download",
                    ),
                    RegexCleanupRule(
                        id = "tts",
                        name = "tts",
                        pattern = "spoken note",
                        flags = "g",
                        enabled = true,
                        appliesTo = "tts",
                    ),
                ),
                chunkSize = 500,
            )

        assertEquals(listOf("Keep this phrase.", "Remove this ."), chunks)
    }

    @Test
    fun prepareTtsChunksSplitLongTextOnNaturalSentenceBoundaries() {
        val first = "${"Alpha ".repeat(55).trim()}."
        val second = "${"Delta ".repeat(55).trim()}."
        val html = "<p>$first</p><p>$second</p>"

        val chunks = TextCleanup.prepareTtsChunks(html, emptyList(), chunkSize = 120)

        assertEquals(listOf(first, second), chunks)
    }

    @Test
    fun prepareTtsChunksEmitOneChunkPerSentence() {
        // One sentence per chunk: even very short sentences are never merged, so the reader can
        // highlight exactly the sentence being spoken.
        val html = "<p>Yes. No. Maybe. Fine.</p>"

        val chunks = TextCleanup.prepareTtsChunks(html, emptyList(), chunkSize = 120)

        assertEquals(listOf("Yes.", "No.", "Maybe.", "Fine."), chunks)
    }

    @Test
    fun htmlToPlainTextRemovesNonTextElementsAndSeparatesBlocks() {
        val html = "<p>Line 1</p><script>bad()</script><p>Line 2</p>"

        assertEquals("Line 1 Line 2", TextCleanup.htmlToPlainText(html))
    }

    @Test
    fun htmlToFormattedTextPreservesParagraphHeadingsBreaksAndTables() {
        assertEquals("Para 1\nPara 2", TextCleanup.htmlToFormattedText("<p>Para 1</p><p>Para 2</p>"))
        assertEquals(
            "Text\n\nHeading\n\nMore text",
            TextCleanup.htmlToFormattedText("<p>Text</p><h2>Heading</h2><p>More text</p>"),
        )
        assertEquals("Line 1\nLine 2", TextCleanup.htmlToFormattedText("Line 1<br/>Line 2"))
        assertEquals(
            "Name | Value\nA | 1",
            TextCleanup.htmlToFormattedText("<table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1</td></tr></table>"),
        )
    }

    @Test
    fun validateRegexRuleNormalizesLiteralInputAndFlags() {
        val result =
            TextCleanup.validateRegexRule(
                "Remove notes",
                "/author note/im",
                "g",
            )

        assertTrue(result.valid)
        assertEquals("author note", result.normalizedPattern)
        assertEquals("gim", result.normalizedFlags)
    }

    @Test
    fun validateRegexRuleRejectsUnsafeNestedQuantifiers() {
        val result =
            TextCleanup.validateRegexRule(
                "Unsafe",
                "(a+)+",
                "g",
            )

        assertFalse(result.valid)
        assertTrue(result.error.orEmpty().contains("Unsafe regex pattern"))
    }

    @Test
    fun sanitizeRegexRulesDropsInvalidRulesNormalizesFieldsAndKeepsLastDuplicateId() {
        val sanitized =
            TextCleanup.sanitizeRegexRules(
                listOf(
                    RegexCleanupRule(id = "", name = "Missing id", pattern = "note", flags = "g"),
                    RegexCleanupRule(id = "bad", name = "Bad", pattern = "(a+)+", flags = "g"),
                    RegexCleanupRule(id = "dup", name = " First ", pattern = "first", flags = "g", appliesTo = "download"),
                    RegexCleanupRule(
                        id = "dup",
                        name = " Second ",
                        pattern = "/second/im",
                        flags = "g",
                        enabled = false,
                        appliesTo = "legacy",
                    ),
                    RegexCleanupRule(id = "literal", name = "Literal", pattern = "/note/s", flags = "ii"),
                ),
            )

        assertEquals(listOf("dup", "literal"), sanitized.map { it.id })
        assertEquals("Second", sanitized[0].name)
        assertEquals("second", sanitized[0].pattern)
        assertEquals("gim", sanitized[0].flags)
        assertFalse(sanitized[0].enabled)
        assertEquals("both", sanitized[0].appliesTo)
        assertEquals("note", sanitized[1].pattern)
        assertEquals("is", sanitized[1].flags)
    }

    @Test
    fun hasSimilarRegexRuleGuardsAgainstDuplicateRules() {
        val rules =
            listOf(
                RegexCleanupRule(id = "one", name = "One", pattern = "note", flags = "gim", appliesTo = "download"),
                RegexCleanupRule(id = "two", name = "Two", pattern = "note", flags = "gim", appliesTo = "tts"),
            )

        assertTrue(TextCleanup.hasSimilarRegexRule(rules, currentId = null, "note", "gim", "download"))
        assertFalse(TextCleanup.hasSimilarRegexRule(rules, currentId = "one", "note", "gim", "download"))
        assertFalse(TextCleanup.hasSimilarRegexRule(rules, currentId = null, "note", "gim", "both"))
    }

    @Test
    fun generateQuickPatternBuildsWholeLineSeparatorRule() {
        val generated = TextCleanup.generateQuickPattern("-", 5, wholeLine = true)

        assertEquals("^[\\s]*\\-{5,}[\\s]*$", generated?.pattern)
        assertEquals("gm", generated?.flags)
        assertEquals("Remove - (5+) separator lines", generated?.name)
    }

    @Test
    fun cleanupSupportsDotMatchesAllRegexFlag() {
        val html = "<p>Before start middle end after</p>"
        val cleaned =
            TextCleanup.applyDownloadCleanup(
                html,
                emptyList(),
                listOf(
                    RegexCleanupRule(
                        id = "dot",
                        name = "dot",
                        pattern = "start.*end",
                        flags = "gs",
                        enabled = true,
                        appliesTo = "download",
                    ),
                ),
            )

        assertFalse(cleaned.contains("start middle end"))
        assertTrue(cleaned.contains("Before"))
    }

    @Test
    fun prepareTtsAnnotatedHtmlTagsgesElementsWithDataTtsGroupIndices() {
        val html = "<p>One</p><p>Two</p><p>Three</p>"

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, emptyList(), chunkSize = 500)

        // One sentence per chunk: each single-sentence paragraph is its own chunk tagged directly.
        assertEquals(listOf("One", "Two", "Three"), annotated.chunks)
        annotated.chunks.indices.forEach { i ->
            assertTrue("missing group $i", annotated.annotatedHtml.contains("data-tts-group=\"$i\""))
        }
        assertTrue(annotated.annotatedHtml.contains("tts-chunk"))
    }

    @Test
    fun prepareTtsAnnotatedHtmlChunksAlignByteForByteWithPrepareTtsChunks() {
        // The annotated HTML's group indices must map 1:1 to the chunk list, AND that chunk list
        // must equal what prepareTtsChunks returns for the same input — this is the invariant the
        // reader highlight + the engine's chunk index rely on.
        val first = "${"Alpha ".repeat(55).trim()}."
        val second = "${"Delta ".repeat(55).trim()}."
        val html = "<p>$first</p><p>$second</p>"

        val plain = TextCleanup.prepareTtsChunks(html, emptyList(), chunkSize = 100)
        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, emptyList(), chunkSize = 100)

        assertEquals(plain, annotated.chunks)
        // Each chunk index present as a data-tts-group attribute, contiguous from 0.
        plain.indices.forEach { i ->
            assertTrue("missing group $i", annotated.annotatedHtml.contains("data-tts-group=\"$i\""))
        }
    }

    @Test
    fun prepareTtsAnnotatedHtmlFallbackReturnsTaggedElementsForEveryChunk() {
        val html = "Alpha ".repeat(180).trim()

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, emptyList(), chunkSize = 10)

        assertEquals(TextCleanup.prepareTtsChunks(html, emptyList(), chunkSize = 10), annotated.chunks)
        assertTrue(annotated.chunks.size > 1)
        annotated.chunks.indices.forEach { i ->
            assertTrue("missing fallback group $i", annotated.annotatedHtml.contains("data-tts-group=\"$i\""))
        }
        assertTrue(annotated.annotatedHtml.contains("tts-chunk"))
    }

    @Test
    fun prepareTtsAnnotatedHtmlSplitsMultiSentenceParagraphIntoPerSentenceSpans() {
        // A paragraph holding more than one sentence is rebuilt as one <span data-tts-group> per
        // sentence so the reader can highlight each sentence independently.
        val first = "${"Alpha ".repeat(55).trim()}."
        val second = "${"Delta ".repeat(55).trim()}."
        val html = "<p>$first $second</p>"

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, emptyList(), chunkSize = 500)

        assertEquals(listOf(first, second), annotated.chunks)
        // Each sentence is its own tagged span (no element carries both groups).
        assertTrue(annotated.annotatedHtml.contains("data-tts-group=\"0\""))
        assertTrue(annotated.annotatedHtml.contains("data-tts-group=\"1\""))
        assertFalse(annotated.annotatedHtml.contains("data-tts-groups=\"0 1\""))
        // The rebuilt spans carry the plain sentence text.
        assertTrue(annotated.annotatedHtml.contains(first))
        assertTrue(annotated.annotatedHtml.contains(second))
    }

    @Test
    fun prepareTtsAnnotatedHtmlTagsEveryContributingSentenceEvenInNestedContainers() {
        // Regression: a container (`blockquote`/`li`/`div`) that holds block children must NOT itself
        // contribute — otherwise its multi-sentence rebuild would `empty()` the container and detach
        // the inner nodes, leaving their chunk indices with no data-tts-group in the rendered HTML.
        val first = "${"Alpha ".repeat(55).trim()}."
        val second = "${"Delta ".repeat(55).trim()}."
        val html = "<blockquote><p>$first</p><p>$second</p></blockquote>"

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, emptyList(), chunkSize = 500)

        // The two inner <p>s each carry one sentence; the container is skipped (no duplicated chunk).
        assertEquals(listOf(first, second), annotated.chunks)
        annotated.chunks.indices.forEach { i ->
            assertTrue("missing group $i", annotated.annotatedHtml.contains("data-tts-group=\"$i\""))
        }
    }

    @Test
    fun prepareTtsAnnotatedHtmlAppliesTtsSpecificCleanupRules() {
        val html = "<body><p>Keep this display-only phrase.</p><p>Remove this spoken note.</p></body>"
        val rules =
            listOf(
                RegexCleanupRule(
                    id = "display",
                    name = "display",
                    pattern = "display-only",
                    flags = "g",
                    enabled = true,
                    appliesTo = "download",
                ),
                RegexCleanupRule(id = "tts", name = "tts", pattern = "spoken note", flags = "g", enabled = true, appliesTo = "tts"),
            )

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, rules, chunkSize = 500)

        // Matches prepareTtsChunks behavior: display rule runs on display text, tts rule on the
        // spoken text, so "spoken note" disappears from the chunks. One sentence per chunk.
        assertEquals(listOf("Keep this phrase.", "Remove this ."), annotated.chunks)
    }

    @Test
    fun prepareTtsAnnotatedHtmlKeepsTtsOnlyCleanupOutOfVisibleReaderText() {
        val html = "<p>Keep this sentence. Remove this spoken note.</p>"
        val rules =
            listOf(
                RegexCleanupRule(
                    id = "tts",
                    name = "tts",
                    pattern = "spoken note",
                    flags = "g",
                    enabled = true,
                    appliesTo = "tts",
                ),
            )

        val annotated = TextCleanup.prepareTtsAnnotatedHtml(html, rules, chunkSize = 500)

        assertEquals(listOf("Keep this sentence.", "Remove this ."), annotated.chunks)
        assertTrue(annotated.annotatedHtml.contains("Keep this sentence."))
        assertTrue(annotated.annotatedHtml.contains("Remove this spoken note."))
    }

    @Test
    fun previewRegexRuleReturnsCleanedTextForMatchingInput() {
        val cleaned = TextCleanup.previewRegexRule("=+", "g", "Intro ===== done")
        assertEquals("Intro  done", cleaned)
    }

    @Test
    fun previewRegexRuleReturnsNullForBlankInput() {
        assertNull(TextCleanup.previewRegexRule("=+", "g", "   "))
    }

    @Test
    fun previewRegexRuleReturnsNullForInvalidPattern() {
        assertNull(TextCleanup.previewRegexRule("(unclosed", "g", "some text"))
    }

    @Test
    fun previewRegexRuleAcceptsRegexLiteralForm() {
        // A /pattern/flags literal should be honored the same as separate fields.
        val cleaned = TextCleanup.previewRegexRule("/-{3,}/g", "g", "Keep---this")
        assertEquals("Keepthis", cleaned)
    }
}
