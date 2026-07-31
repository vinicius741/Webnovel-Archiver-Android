@file:Suppress("ktlint:standard:function-signature")

package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule

/** Test-only compatibility helper; production code uses the owning cleanup objects directly. */
object TextCleanup {
    fun sanitizeRegexRules(rules: List<RegexCleanupRule>): MutableList<RegexCleanupRule> =
        RegexRuleCleanup.sanitizeRegexRules(rules)

    fun hasSimilarRegexRule(
        rules: List<RegexCleanupRule>,
        currentId: String?,
        normalizedPattern: String,
        normalizedFlags: String,
        appliesTo: String,
    ): Boolean =
        RegexRuleCleanup.hasSimilarRegexRule(rules, currentId, normalizedPattern, normalizedFlags, appliesTo)

    fun validateRegexRule(
        name: String,
        patternInput: String,
        flagsInput: String,
    ) = RegexRuleCleanup.validateRegexRule(name, patternInput, flagsInput)

    fun generateQuickPattern(
        characters: String,
        minCount: Int,
        wholeLine: Boolean,
    ) = RegexRuleCleanup.generateQuickPattern(characters, minCount, wholeLine)

    fun previewRegexRule(
        pattern: String,
        flags: String,
        sampleInput: String,
    ): String? = RegexRuleCleanup.previewRegexRule(pattern, flags, sampleInput)

    fun htmlToFormattedText(html: String): String = HtmlCleanup.htmlToFormattedText(html)

    @Suppress("UNUSED_PARAMETER")
    fun prepareTtsChunks(
        html: String,
        rules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> = TtsTextPreparation.prepareTtsChunks(html, rules)

    @Suppress("UNUSED_PARAMETER")
    fun prepareTtsAnnotatedHtml(
        html: String,
        rules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): TtsTextPreparation.TtsAnnotatedHtml = TtsTextPreparation.prepareTtsAnnotatedHtml(html, rules)
}
