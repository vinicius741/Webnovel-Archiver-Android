package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
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
        assertFalse(result.html.contains("Remove this", ignoreCase = true))
        assertTrue(result.html.contains("Keep this"))
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
