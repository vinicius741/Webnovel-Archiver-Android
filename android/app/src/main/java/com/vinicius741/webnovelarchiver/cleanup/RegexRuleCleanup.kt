package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule

/** Regex rule validation, normalization, and editor helpers. */
object RegexRuleCleanup {
    private val regexFlagOrder = listOf('g', 'i', 'm', 's', 'u')
    private val allowedRegexFlags = regexFlagOrder.toSet()
    private val regexLiteral = Regex("^/((?:\\\\.|[^\\\\/])*)/([a-z]*)$", RegexOption.IGNORE_CASE)
    private val riskyRegexChecks =
        listOf(
            Regex("\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
            Regex("\\((?:[^()\\\\]|\\\\.)*\\.\\*(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
            Regex("\\((?:[^()\\\\]|\\\\.)*\\\\\\d+(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
        )
    private const val MAX_REGEX_PATTERN_LENGTH = 500
    private const val MAX_RULE_NAME_LENGTH = 80

    internal fun regexOptions(flags: String): Set<RegexOption> =
        buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }

    /** Builds the stateless cleanup function used while preparing TTS and reader text. */
    @Suppress("TooGenericExceptionCaught")
    internal fun regexRunner(
        rules: List<RegexCleanupRule>,
        target: String,
    ): (String) -> String {
        val compiled =
            rules
                .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == target) && !RegexCircuitBreaker.isDisabled(it) }
                .mapNotNull { rule ->
                    runCatching { Regex(rule.pattern, regexOptions(rule.flags)) }.getOrNull()?.let { it to rule }
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

    data class RegexValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val normalizedPattern: String? = null,
        val normalizedFlags: String? = null,
    )

    data class QuickPattern(
        val pattern: String,
        val flags: String,
        val name: String,
    )

    fun sanitizeRegexRules(rules: List<RegexCleanupRule>): MutableList<RegexCleanupRule> {
        val unique = linkedMapOf<String, RegexCleanupRule>()
        rules.forEach { rule ->
            val id = rule.id.trim()
            if (id.isBlank()) return@forEach
            val name = rule.name.trim()
            val validation = validateRegexRule(name, rule.pattern, rule.flags)
            if (!validation.valid) return@forEach
            val appliesTo =
                when (rule.appliesTo.trim().lowercase()) {
                    "download", "tts", "both" -> rule.appliesTo.trim().lowercase()
                    else -> "both"
                }
            unique[id] =
                rule.copy(
                    id = id,
                    name = name,
                    pattern = validation.normalizedPattern ?: rule.pattern.trim(),
                    flags = validation.normalizedFlags.orEmpty(),
                    appliesTo = appliesTo,
                )
        }
        return unique.values.toMutableList()
    }

    fun hasSimilarRegexRule(
        rules: List<RegexCleanupRule>,
        currentId: String?,
        normalizedPattern: String,
        normalizedFlags: String,
        appliesTo: String,
    ): Boolean {
        val target =
            when (appliesTo.trim().lowercase()) {
                "download", "tts", "both" -> appliesTo.trim().lowercase()
                else -> "both"
            }
        return rules.any { rule ->
            rule.id != currentId &&
                rule.pattern == normalizedPattern &&
                rule.flags == normalizedFlags &&
                rule.appliesTo == target
        }
    }

    fun validateRegexRule(
        name: String,
        patternInput: String,
        flagsInput: String,
    ): RegexValidationResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return RegexValidationResult(false, "Rule name is required.")
        if (trimmedName.length > MAX_RULE_NAME_LENGTH) {
            return RegexValidationResult(false, "Rule name must be $MAX_RULE_NAME_LENGTH characters or fewer.")
        }

        val normalizedInput = normalizeRegexInput(patternInput, flagsInput)
        normalizedInput.error?.let { return RegexValidationResult(false, it) }
        val pattern = normalizedInput.pattern
        val flags = normalizedInput.flags

        if (pattern.isBlank()) return RegexValidationResult(false, "Regex pattern is required.")
        if (pattern.length > MAX_REGEX_PATTERN_LENGTH) {
            return RegexValidationResult(false, "Regex pattern must be $MAX_REGEX_PATTERN_LENGTH characters or fewer.")
        }
        flags.forEach { flag ->
            if (flag !in allowedRegexFlags) {
                return RegexValidationResult(false, "Unsupported regex flag: \"$flag\". Allowed flags: ${regexFlagOrder.joinToString("")}")
            }
        }
        if (riskyRegexChecks.any { it.containsMatchIn(pattern) }) {
            return RegexValidationResult(false, "Unsafe regex pattern: nested quantified groups can cause very slow matching.")
        }

        val normalizedFlags = normalizeRegexFlags(flags)
        return runCatching {
            Regex(pattern, regexOptions(normalizedFlags.replace("g", "")))
            RegexValidationResult(true, normalizedPattern = pattern, normalizedFlags = normalizedFlags)
        }.getOrElse { RegexValidationResult(false, "Invalid regex: ${it.message}") }
    }

    fun generateQuickPattern(
        characters: String,
        minCount: Int,
        wholeLine: Boolean,
    ): QuickPattern? {
        val value = characters.trim()
        if (value.isBlank() || minCount < 1) return null
        val escaped = escapeRegexLiteral(value)
        val core = if (value.length > 1) "(?:$escaped){$minCount,}" else "$escaped{$minCount,}"
        val pattern = if (wholeLine) "^[\\s]*$core[\\s]*$" else core
        val flags = if (wholeLine) "gm" else "g"
        val display = if (value.length > 4) "${value.take(4)}..." else value
        val scope = if (wholeLine) "separator lines" else "patterns"
        return QuickPattern(pattern, flags, "Remove $display ($minCount+) $scope")
    }

    /** Validates an in-progress editor rule and applies it to [sampleInput]. */
    fun previewRegexRule(
        pattern: String,
        flags: String,
        sampleInput: String,
    ): String? {
        if (sampleInput.isBlank()) return null
        val validation = validateRegexRule("preview", pattern, flags)
        if (!validation.valid) return null
        val normalizedPattern = validation.normalizedPattern ?: return null
        val normalizedFlags = validation.normalizedFlags ?: ""
        val compiled =
            runCatching {
                Regex(normalizedPattern, regexOptions(normalizedFlags))
            }.getOrNull() ?: return null
        return compiled.replace(sampleInput, "")
    }

    private data class NormalizedRegexInput(
        val pattern: String,
        val flags: String,
        val error: String? = null,
    )

    private fun normalizeRegexInput(
        patternInput: String,
        flagsInput: String,
    ): NormalizedRegexInput {
        val trimmedPattern = patternInput.trim()
        val trimmedFlags = flagsInput.trim().lowercase()
        val maybeLiteral = trimmedPattern.startsWith("/") && trimmedPattern.lastIndexOf("/") > 0
        val match = regexLiteral.matchEntire(trimmedPattern)

        if (match == null && maybeLiteral) {
            return NormalizedRegexInput(
                trimmedPattern,
                trimmedFlags,
                "Invalid regex literal. Use /pattern/flags or provide pattern and flags separately.",
            )
        }
        if (match == null) return NormalizedRegexInput(trimmedPattern, trimmedFlags)

        return NormalizedRegexInput(match.groupValues[1], trimmedFlags + match.groupValues[2].lowercase())
    }

    private fun normalizeRegexFlags(flags: String): String {
        val unique = flags.trim().lowercase().toSet()
        return regexFlagOrder.filter { it in unique }.joinToString("")
    }

    private fun escapeRegexLiteral(value: String): String {
        val special = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\', '-')
        return buildString {
            value.forEach { char ->
                if (char in special) append('\\')
                append(char)
            }
        }
    }
}
