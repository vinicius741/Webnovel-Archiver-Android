package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets.Preset
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexCleanupPresetsTest {
    @Test
    fun punctuationPresetRemovesOnlyCopiesAboveFive() {
        val rule = rule(Preset.REPEATED_PUNCTUATION)
        val clean = RegexRuleCleanup.regexRunner(listOf(rule), "tts")
        (1..30).forEach { count ->
            val input = "Before ${"-".repeat(count)} after"
            assertEquals("Before ${"-".repeat(count.coerceAtMost(5))} after", clean(input))
        }
        assertEquals("-----", clean("-------------------"))
        assertEquals("----", clean("----"))
        assertEquals("-----", clean("-----"))
    }

    @Test
    fun punctuationPresetHandlesSymbolsUnicodeAndMultipleRunsWithoutChangingWords() {
        val clean = RegexRuleCleanup.regexRunner(listOf(rule(Preset.REPEATED_PUNCTUATION)), "tts")
        val symbols = listOf("-", "=", "*", ".", "!", "_", "—", "−", "★", "😀")
        symbols.forEach { symbol -> assertEquals(symbol.repeat(5), clean(symbol.repeat(20))) }
        val prose = "Noooooooo 11111111\n\n   text"
        assertEquals(prose, clean(prose))
        assertEquals("----- !!! =====\n*****", clean("------- !!! ============\n********"))
        assertEquals("-=-=--=-=", clean("-=-=--=-="))
        assertEquals("-----\n-----", clean("------\n------"))
    }

    @Test
    fun dashPresetLeavesOtherPunctuationAloneAndSupportsAdjustableLimit() {
        val clean = RegexRuleCleanup.regexRunner(listOf(rule(Preset.REPEATED_DASHES, 6)), "tts")
        listOf("-", "—", "–", "−").forEach { dash -> assertEquals(dash.repeat(6), clean(dash.repeat(20))) }
        assertEquals("!!!!!!!! ===========", clean("!!!!!!!! ==========="))
    }

    @Test
    fun allCharactersPresetShortensStretchedWordsAndNumbersButPreservesWhitespace() {
        val clean = RegexRuleCleanup.regexRunner(listOf(rule(Preset.REPEATED_CHARACTERS)), "tts")
        assertEquals("Nooooo 11111 -----", clean("Nooooooooooooo 111111111111 -----------"))
        assertEquals("\n".repeat(10) + " ".repeat(10), clean("\n".repeat(10) + " ".repeat(10)))
    }

    @Test
    fun separatorPresetRemovesOnlySymbolLinesAndPreservesLineBoundaries() {
        val clean = RegexRuleCleanup.regexRunner(listOf(rule(Preset.SEPARATOR_LINES)), "tts")
        assertEquals(
            "First\n\n\nNext --- scene\n--\n123456\n",
            clean("First\n  -----  \n**==**\nNext --- scene\n--\n123456\n"),
        )
        assertEquals("Before\r\n\r\nAfter", clean("Before\r\n\t—————\t\r\nAfter"))
        assertEquals("Before\n\nAfter", clean("Before\n\u00a0*****\u00a0\nAfter"))
    }

    @Test
    fun presetsValidateAndRoundTripThroughExistingRuleSanitization() {
        Preset.values().forEach { preset ->
            listOf(1, preset.defaultCount, RegexCleanupPresets.MAX_COUNT).forEach { count ->
                val original = rule(preset, count)
                val validation = RegexRuleCleanup.validateRegexRule(original.name, original.pattern, original.flags)
                assertTrue(validation.error.orEmpty(), validation.valid)
                assertEquals(original, RegexRuleCleanup.sanitizeRegexRules(listOf(original)).single())
                assertEquals(RegexCleanupPresets.Configuration(preset, count), RegexCleanupPresets.identify(original))
            }
        }
        assertNull(RegexCleanupPresets.identify(RegexCleanupRule(id = "custom", name = "Custom", pattern = "foo", flags = "g")))
        assertNull(RegexCleanupPresets.generate(Preset.REPEATED_PUNCTUATION, 0))
        assertNull(RegexCleanupPresets.generate(Preset.REPEATED_PUNCTUATION, RegexCleanupPresets.MAX_COUNT + 1))
    }

    @Test
    fun downloadTtsAndPreviewAgreeAndCleanupIsIdempotent() {
        val rule = rule(Preset.REPEATED_PUNCTUATION).copy(appliesTo = "both")
        val input = "Before ------------------- after"
        val expected = "Before ----- after"
        val html = "<p>$input</p>"
        val cleaned = CleanupEngine().applyDownload(html, emptyList(), listOf(rule))
        assertEquals(expected, Jsoup.parseBodyFragment(cleaned).text())
        assertEquals(expected, TtsTextPreparation.prepareTtsChunks(html, listOf(rule)).joinToString(" "))
        assertEquals(expected, RegexRuleCleanup.previewRegexRule(rule.pattern, rule.flags, input))
        assertEquals(cleaned, CleanupEngine().applyDownload(cleaned, emptyList(), listOf(rule)))
        assertNotNull(RegexCleanupPresets.identify(rule))
    }

    @Test
    fun presetHonorsTargetAndEnabledState() {
        val rule = rule(Preset.REPEATED_PUNCTUATION).copy(appliesTo = "tts")
        val input = "-------------------"
        assertEquals(input, RegexRuleCleanup.regexRunner(listOf(rule), "download")(input))
        assertEquals("-----", RegexRuleCleanup.regexRunner(listOf(rule), "tts")(input))
        assertEquals(input, RegexRuleCleanup.regexRunner(listOf(rule.copy(enabled = false)), "tts")(input))
        assertEquals(input, Jsoup.parseBodyFragment(CleanupEngine().applyDownload("<p>$input</p>", emptyList(), listOf(rule))).text())
    }

    private fun rule(
        preset: Preset,
        count: Int = preset.defaultCount,
    ): RegexCleanupRule {
        val generated = requireNotNull(RegexCleanupPresets.generate(preset, count))
        return RegexCleanupRule(
            id = "${preset.name}_$count",
            name = generated.name,
            pattern = generated.pattern,
            flags = generated.flags,
            appliesTo = "both",
        )
    }
}
