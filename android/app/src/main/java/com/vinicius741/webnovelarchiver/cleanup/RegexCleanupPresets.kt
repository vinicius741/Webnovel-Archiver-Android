package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule

/** Generates ordinary deletion rules, compatible with existing storage, download and TTS cleanup. */
object RegexCleanupPresets {
    const val MAX_COUNT = 50

    enum class Preset(
        val title: String,
        val description: String,
        val defaultCount: Int,
        val sample: String,
    ) {
        REPEATED_PUNCTUATION(
            "Shorten repeated punctuation",
            "Keep a limited number of the same punctuation mark or symbol in a row. Covers dashes, dots, stars and more. Letters and numbers stay unchanged.",
            5,
            "Before ------------------- after\nWow!!!!!!!!!!!!\nStars ************",
        ),
        REPEATED_DASHES(
            "Shorten repeated dashes",
            "Limit runs of the same dash, including long dashes and minus signs. Other punctuation stays unchanged.",
            5,
            "Before ------------------- after\nNext ———————————— scene",
        ),
        REPEATED_CHARACTERS(
            "Shorten any repeated character",
            "Limit identical letters, numbers, punctuation or symbols in a row. Also shortens stretched words. Spaces and line breaks stay unchanged.",
            5,
            "Nooooooooooooo!\nBefore ------------------- after\nCode 111111111111",
        ),
        SEPARATOR_LINES(
            "Remove separator lines",
            "Remove lines made entirely of punctuation or symbols, such as ----- or **==**. Lines with words or numbers stay unchanged.",
            3,
            "First scene\n-------------------\n**==**\nNext scene",
        ),
    }

    data class Configuration(
        val preset: Preset,
        val count: Int,
    )

    fun generate(
        preset: Preset,
        count: Int = preset.defaultCount,
    ): RegexRuleCleanup.QuickPattern? {
        if (count !in 1..MAX_COUNT) return null
        val pattern =
            when (preset) {
                // Match one character only when at least [count] identical characters follow it.
                // Deleting these matches leaves exactly [count], without replacement support.
                Preset.REPEATED_PUNCTUATION -> "([\\p{P}\\p{S}])(?=\\1{$count})"
                Preset.REPEATED_DASHES -> "([\\p{Pd}\\u2212])(?=\\1{$count})"
                Preset.REPEATED_CHARACTERS -> "(\\S)(?=\\1{$count})"
                // Horizontal whitespace must not consume adjacent lines or join prose paragraphs.
                Preset.SEPARATOR_LINES -> "^\\h*[\\p{P}\\p{S}]{$count,}\\h*$"
            }
        val flags = if (preset == Preset.SEPARATOR_LINES) "gm" else "g"
        val name =
            if (preset == Preset.SEPARATOR_LINES) {
                "${preset.title} ($count+ symbols)"
            } else {
                "${preset.title} (keep $count)"
            }
        return RegexRuleCleanup.QuickPattern(pattern, flags, name)
    }

    /** Reopens saved presets in the plain-language editor without adding persistence fields. */
    fun identify(rule: RegexCleanupRule): Configuration? {
        for (preset in Preset.values()) {
            for (count in 1..MAX_COUNT) {
                val generated = generate(preset, count) ?: continue
                if (rule.pattern == generated.pattern && rule.flags == generated.flags) {
                    return Configuration(preset, count)
                }
            }
        }
        return null
    }
}
