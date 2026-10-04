package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the cached cleanup engine. Verifies the cache recompiles only on settings change and
 * that its output preserves the cleanup contract.
 */
class CleanupEngineTest {
    @Test
    fun applyDownloadWithStatsCountsEverySentenceRemoval() {
        val result =
            CleanupEngine().applyDownloadWithStats(
                "<p>Remove this. Keep this.</p><p>remove   this.</p>",
                listOf("Remove this."),
                emptyList(),
            )

        assertEquals(2, result.sentencesRemoved)
        assertEquals(0, result.regexMatchesRemoved)
        assertFalse(result.html.contains("Remove this", ignoreCase = true))
        assertTrue(result.html.contains("Keep this"))
    }

    @Test
    fun countsRegexMatchesAcrossTextNodesAndKeepsSentenceCountsSeparate() {
        val rules = listOf(RegexCleanupRule(id = "regex-count", name = "Remove ads", pattern = "buy now", flags = "i", appliesTo = "both"))
        val result =
            CleanupEngine().applyDownloadWithStats(
                "<p>Remove this. Buy now, BUY NOW.</p><p>buy now <b>Buy now</b> Keep this.</p>",
                listOf("Remove this."),
                rules,
            )
        assertEquals(1, result.sentencesRemoved)
        assertEquals(4, result.regexMatchesRemoved)
        assertFalse(result.html.contains("buy now", ignoreCase = true))
        assertTrue(result.html.contains("Keep this."))
    }

    @Test
    fun countsExcessCharactersDeletedByRepetitionPresetAndReportsZeroOnSecondPass() {
        val preset = requireNotNull(RegexCleanupPresets.generate(RegexCleanupPresets.Preset.REPEATED_PUNCTUATION))
        val rule =
            RegexCleanupRule(
                id = "preset-count",
                name = preset.name,
                pattern = preset.pattern,
                flags = preset.flags,
                appliesTo = "download",
            )
        val engine = CleanupEngine()
        val result =
            engine.applyDownloadWithStats(
                "<p>Before ${"-".repeat(19)} after</p><p>!!!!! ${"*".repeat(8)}</p>",
                emptyList(),
                listOf(rule),
            )
        assertEquals(0, result.sentencesRemoved)
        assertEquals(17, result.regexMatchesRemoved)
        assertEquals("Before ----- after !!!!! *****", Jsoup.parseBodyFragment(result.html).text())
        val unchanged = engine.applyDownloadWithStats(result.html, emptyList(), listOf(rule))
        assertEquals(result.html, unchanged.html)
        assertEquals(0, unchanged.regexMatchesRemoved)
    }

    @Test
    fun skipsDisabledTtsOnlyAndNonMatchingRulesInCounts() {
        val matching = RegexCleanupRule(id = "count-disabled", name = "Disabled", pattern = "marker", flags = "g", enabled = false)
        val ttsOnly = matching.copy(id = "count-tts", name = "TTS", enabled = true, appliesTo = "tts")
        val nonMatching = matching.copy(id = "count-absent", pattern = "absent", enabled = true)
        val result = CleanupEngine().applyDownloadWithStats("<p>marker</p>", emptyList(), listOf(matching, ttsOnly, nonMatching))
        assertEquals(0, result.regexMatchesRemoved)
        assertTrue(result.html.contains("marker"))
    }

    @Test
    fun zeroWidthMatchesDoNotCountAsRemovals() {
        val rule = RegexCleanupRule(id = "count-empty", name = "Empty matches", pattern = "^|$|(?=keep)", flags = "gm")
        val result = CleanupEngine().applyDownloadWithStats("<p>keep text</p>", emptyList(), listOf(rule))
        assertEquals(0, result.regexMatchesRemoved)
        assertEquals("keep text", Jsoup.parseBodyFragment(result.html).text())
    }

    @Test
    fun overlappingSentenceAndRegexRulesCountOnlyTextActuallyRemoved() {
        val rules =
            listOf(
                RegexCleanupRule(id = "count-overlap", name = "Same sentence", pattern = "Remove this", flags = "g"),
                RegexCleanupRule(id = "count-first", name = "First", pattern = "marker", flags = "g"),
                RegexCleanupRule(id = "count-second", name = "Second", pattern = "marker", flags = "g"),
            )
        val result = CleanupEngine().applyDownloadWithStats("<p>Remove this marker marker</p>", listOf("Remove this"), rules)
        assertEquals(1, result.sentencesRemoved)
        assertEquals(2, result.regexMatchesRemoved)
    }

    @Test
    fun cachedSnapshotReusedWhenInputsUnchanged() {
        val engine = CleanupEngine()
        val rules =
            listOf(RegexCleanupRule(id = "r1", name = "ads", pattern = "/buy now/gi", flags = "gi", enabled = true, appliesTo = "both"))
        val sentences = listOf("Patreon exclusive")

        val first = engine.compiled(sentences, rules)
        val second = engine.compiled(sentences, rules)
        assertSame(first, second)
    }

    @Test
    fun cachedSnapshotRecompiledWhenRulesChange() {
        val engine = CleanupEngine()
        val rules = listOf(RegexCleanupRule(id = "r1", name = "ads", pattern = "/buy/gi", flags = "gi"))
        val first = engine.compiled(listOf("a"), rules)
        val changedRules = listOf(rules.first().copy(pattern = "/free/gi"))
        val second = engine.compiled(listOf("a"), changedRules)
        assertNotSame(first, second)
    }

    @Test
    fun applyDownloadRemovesSentencesAndRegexRules() {
        val html = "<p>Buy now! Patreon exclusive content here.</p>"
        val sentences = listOf("Patreon exclusive")
        val rules = listOf(RegexCleanupRule(id = "r1", name = "ads", pattern = "Buy now", flags = "i", enabled = true, appliesTo = "both"))
        val actual = CleanupEngine().applyDownload(html, sentences, rules)
        // Both the regex rule ("Buy now") and the sentence ("Patreon exclusive") are stripped.
        assertTrue(!actual.contains("Buy now", ignoreCase = true))
        assertTrue(!actual.contains("Patreon exclusive", ignoreCase = true))
        assertTrue(actual.contains("content here"))
    }

    @Test
    fun engineCompilesBothDownloadAndTtsRegexSets() {
        val engine = CleanupEngine()
        val rules =
            listOf(
                RegexCleanupRule(id = "d", name = "dl", pattern = "/foo/g", flags = "g", appliesTo = "download"),
                RegexCleanupRule(id = "t", name = "tt", pattern = "/bar/g", flags = "g", appliesTo = "tts"),
            )
        val compiled = engine.compiled(emptyList(), rules)
        assertEquals(1, compiled.downloadRules.size)
        assertEquals(1, compiled.ttsRules.size)
        val compiledRule = compiled.downloadRules.single().source
        assertEquals("d", compiledRule.id)
    }
}
