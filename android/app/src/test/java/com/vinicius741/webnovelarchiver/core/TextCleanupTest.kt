package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanupTest {
    @Test
    fun cleanupAppliesToNestedTextNodesAndSkipsScripts() {
        val html = """
            <div>
              <p>Hello <strong>Support me on Patreon</strong> world</p>
              <p>Keep --- this marker</p>
              <script>Support me on Patreon</script>
            </div>
        """.trimIndent()

        val cleaned = TextCleanup.applyDownloadCleanup(
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
        val html = """
            <body>
              <p>Keep this display-only phrase.</p>
              <p>Remove this spoken note.</p>
            </body>
        """.trimIndent()

        val chunks = TextCleanup.prepareTtsChunks(
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

        assertEquals(listOf("Keep this phrase. Remove this ."), chunks)
    }

    @Test
    fun prepareTtsChunksSplitsOnConfiguredChunkSize() {
        val first = "Alpha ".repeat(25).trim()
        val second = "Delta ".repeat(25).trim()
        val html = "<p>$first</p><p>$second</p>"

        val chunks = TextCleanup.prepareTtsChunks(html, emptyList(), chunkSize = 120)

        assertEquals(listOf(first, second), chunks)
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
        val result = TextCleanup.validateRegexRule(
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
        val result = TextCleanup.validateRegexRule(
            "Unsafe",
            "(a+)+",
            "g",
        )

        assertFalse(result.valid)
        assertTrue(result.error.orEmpty().contains("Unsafe regex pattern"))
    }

    @Test
    fun sanitizeRegexRulesDropsInvalidRulesNormalizesFieldsAndKeepsLastDuplicateId() {
        val sanitized = TextCleanup.sanitizeRegexRules(
            listOf(
                RegexCleanupRule(id = "", name = "Missing id", pattern = "note", flags = "g"),
                RegexCleanupRule(id = "bad", name = "Bad", pattern = "(a+)+", flags = "g"),
                RegexCleanupRule(id = "dup", name = " First ", pattern = "first", flags = "g", appliesTo = "download"),
                RegexCleanupRule(id = "dup", name = " Second ", pattern = "/second/im", flags = "g", enabled = false, appliesTo = "legacy"),
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
    fun hasSimilarRegexRuleMatchesReactNativeDuplicateRuleGuard() {
        val rules = listOf(
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
        val cleaned = TextCleanup.applyDownloadCleanup(
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
}
