package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule

/**
 * Stateless HTML cleanup, regex rule validation, and TTS chunking (Maintainability M1: split out of
 * Engines.kt). The implementation now lives in three consumer-domain siblings and this object is a
 * thin facade that preserves the original call surface ([RegexRuleCleanup], [HtmlCleanup],
 * [TtsTextPreparation]) so the many callers — the cleanup UI, download/reader engines, TTS engine,
 * and storage — are unchanged. It also owns the shared `internal` regex helpers those siblings use.
 *
 * Stateful, cached application flows through [CleanupEngine]; this facade keeps the stateless,
 * tested entry points.
 */
object TextCleanup {
    // ── Regex rule management (cleanup UI) → RegexRuleCleanup ──────────────────────────────

    fun sanitizeRegexRules(rules: List<RegexCleanupRule>): MutableList<RegexCleanupRule> = RegexRuleCleanup.sanitizeRegexRules(rules)

    fun hasSimilarRegexRule(
        rules: List<RegexCleanupRule>,
        currentId: String?,
        normalizedPattern: String,
        normalizedFlags: String,
        appliesTo: String,
    ): Boolean = RegexRuleCleanup.hasSimilarRegexRule(rules, currentId, normalizedPattern, normalizedFlags, appliesTo)

    fun validateRegexRule(
        name: String,
        patternInput: String,
        flagsInput: String,
    ): RegexRuleCleanup.RegexValidationResult = RegexRuleCleanup.validateRegexRule(name, patternInput, flagsInput)

    fun generateQuickPattern(
        characters: String,
        minCount: Int,
        wholeLine: Boolean,
    ): RegexRuleCleanup.QuickPattern? = RegexRuleCleanup.generateQuickPattern(characters, minCount, wholeLine)

    fun previewRegexRule(
        pattern: String,
        flags: String,
        sampleInput: String,
    ): String? = RegexRuleCleanup.previewRegexRule(pattern, flags, sampleInput)

    // ── HTML-to-text transformation (download engine + reader) → HtmlCleanup ───────────────

    fun applyDownloadCleanup(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String = HtmlCleanup.applyDownloadCleanup(html, sentences, rules)

    fun htmlToPlainText(html: String): String = HtmlCleanup.htmlToPlainText(html)

    fun htmlToFormattedText(html: String): String = HtmlCleanup.htmlToFormattedText(html)

    // ── TTS preparation (TTS engine + reader) → TtsTextPreparation ─────────────────────────

    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> = TtsTextPreparation.prepareTtsChunks(html, regexRules, chunkSize)

    fun prepareTtsAnnotatedHtml(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): TtsTextPreparation.TtsAnnotatedHtml = TtsTextPreparation.prepareTtsAnnotatedHtml(html, regexRules, chunkSize)

    // ── Shared internal regex helpers (used by the three siblings + CleanupEngine) ─────────

    @Suppress("TooGenericExceptionCaught") // Rec 7: a user regex can throw on a pathological input; we catch broadly to circuit-break the rule instead of aborting TTS/reader preparation.
    internal fun regexRunner(
        rules: List<RegexCleanupRule>,
        target: String,
    ): (String) -> String {
        // Audit Rec 7: skip circuit-broken rules and time each application so a pathological pattern
        // is disabled for the session after repeated slow/failing runs. This is the TTS/reader path;
        // CleanupEngine.applyDownloadWithStats instruments the download path through the same breaker.
        val compiled =
            rules
                .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == target) && !RegexCircuitBreaker.isDisabled(it) }
                .mapNotNull { rule ->
                    runCatching {
                        Regex(rule.pattern, regexOptions(rule.flags))
                    }.getOrNull()?.let { it to rule }
                }
        return { input ->
            compiled.fold(input) { text, (regex, rule) ->
                val start = System.nanoTime()
                val result =
                    try {
                        regex.replace(text, "")
                    } catch (_: Throwable) {
                        RegexCircuitBreaker.report(rule, System.nanoTime() - start, failed = true)
                        return@fold text
                    }
                RegexCircuitBreaker.report(rule, System.nanoTime() - start)
                result
            }
        }
    }

    internal fun regexOptions(flags: String): Set<RegexOption> {
        val opts = mutableSetOf<RegexOption>()
        if ('i' in flags) opts.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) opts.add(RegexOption.MULTILINE)
        if ('s' in flags) opts.add(RegexOption.DOT_MATCHES_ALL)
        return opts
    }
}
