package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the cached cleanup engine (Speed S6). Verifies the cache recompiles only on settings
 * change and that its output matches the stateless TextCleanup path.
 */
class CleanupEngineTest {
    @Test
    fun cachedSnapshotReusedWhenInputsUnchanged() {
        val engine = CleanupEngine()
        val rules = listOf(RegexCleanupRule(id = "r1", name = "ads", pattern = "/buy now/gi", flags = "gi", enabled = true, appliesTo = "both"))
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
    fun applyDownloadMatchesTextCleanupOutput() {
        val html = "<p>Buy now! Patreon exclusive content here.</p>"
        val sentences = listOf("Patreon exclusive")
        val rules = listOf(RegexCleanupRule(id = "r1", name = "ads", pattern = "Buy now", flags = "i", enabled = true, appliesTo = "both"))
        val expected = TextCleanup.applyDownloadCleanup(html, sentences, rules)
        val actual = CleanupEngine().applyDownload(html, sentences, rules)
        assertEquals(expected, actual)
        // Both the regex rule ("Buy now") and the sentence ("Patreon exclusive") are stripped.
        assertTrue(!actual.contains("Buy now", ignoreCase = true))
        assertTrue(!actual.contains("Patreon exclusive", ignoreCase = true))
    }

    @Test
    fun engineCompilesBothDownloadAndTtsRegexSets() {
        val engine = CleanupEngine()
        val rules = listOf(
            RegexCleanupRule(id = "d", name = "dl", pattern = "/foo/g", flags = "g", appliesTo = "download"),
            RegexCleanupRule(id = "t", name = "tt", pattern = "/bar/g", flags = "g", appliesTo = "tts"),
        )
        val compiled = engine.compiled(emptyList(), rules)
        assertEquals(1, compiled.downloadRegexes.size)
        assertEquals(1, compiled.ttsRegexes.size)
    }
}
