package com.vinicius741.webnovelarchiver.feature.settings

import java.util.Locale

/** The one-line current values under each Settings hub row. */
internal object SettingsHubPlanning {
    /** "Kokoro · Bella · 1.8×"; the rate is left out at normal speed. */
    fun voiceSummary(
        engine: String,
        voice: String,
        rate: Float,
    ): String =
        buildList {
            add(engine.removeSuffix(" (built in)"))
            add(voice.substringBefore(" ("))
            if (rate != 1f) add(rateLabel(rate))
        }.joinToString(" · ")

    /** 1.8 → "1.8×", 1.25 → "1.25×", 2 → "2×". */
    fun rateLabel(rate: Float): String =
        String
            .format(Locale.US, "%.2f", rate)
            .trimEnd('0')
            .trimEnd('.') + "×"

    fun cleanupSummary(
        rules: Int,
        sentences: Int,
    ): String =
        when {
            rules == 0 && sentences == 0 -> "No rules yet"
            else -> listOf(plural(rules, "rule"), plural(sentences, "sentence")).joinToString(" · ")
        }

    /** "3 tabs · Reading, Plan, Paused"; names are cut after a few to keep one line. */
    fun tabsSummary(names: List<String>): String =
        if (names.isEmpty()) {
            "No tabs yet"
        } else {
            val shown = names.take(MAX_TAB_NAMES).joinToString(", ") + if (names.size > MAX_TAB_NAMES) ", …" else ""
            "${plural(names.size, "tab")} · $shown"
        }

    fun downloadsSummary(
        parallelSources: Int,
        range: PaceRange,
    ): String = "${plural(parallelSources, "source")} at once · ${DownloadPacePlanning.rangeLabel(range)} delay"

    fun plural(
        count: Int,
        noun: String,
    ): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private const val MAX_TAB_NAMES = 4
}
