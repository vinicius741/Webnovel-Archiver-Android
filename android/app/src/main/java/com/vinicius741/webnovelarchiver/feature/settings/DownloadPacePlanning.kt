package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import java.math.BigDecimal
import java.math.RoundingMode

/** A delay range in milliseconds, always ordered min ≤ max. */
internal data class PaceRange(
    val minMs: Long,
    val maxMs: Long,
)

/** A "Quick fill" pace: only fills the delay steppers, which stay editable. */
internal data class PacePreset(
    val label: String,
    val range: PaceRange,
)

/**
 * Download pace rules for Download settings: keeping the delay pair ordered while one stepper
 * moves, reading and writing a site's override (keyed by provider id, with the legacy name key
 * folded away), quick-fill presets, and the plain-language summaries.
 */
internal object DownloadPacePlanning {
    val presets: List<PacePreset> =
        listOf(
            PacePreset("Gentle", PaceRange(2_000, 5_000)),
            PacePreset("Balanced", PaceRange(1_000, 3_000)),
            PacePreset("Fast", PaceRange(300, 1_200)),
        )

    /** The min moved: a max below it is pulled up to match. */
    fun withMin(
        range: PaceRange,
        minMs: Long,
    ): PaceRange = PaceRange(minMs, maxOf(range.maxMs, minMs))

    /** The max moved: a min above it is pulled down to match. */
    fun withMax(
        range: PaceRange,
        maxMs: Long,
    ): PaceRange = PaceRange(minOf(range.minMs, maxMs), maxMs)

    /** The override saved for a site, under its provider id or its legacy display-name key. */
    fun overrideFor(
        overrides: Map<String, SourceDownloadSettings>,
        providerId: String,
        providerName: String,
    ): SourceDownloadSettings? = overrides[providerId] ?: overrides[providerName]

    /**
     * The overrides with this site's pace replaced: [range] null means "use the defaults". The
     * legacy name key is always dropped so the site has exactly one entry, under its id.
     */
    fun withOverride(
        overrides: Map<String, SourceDownloadSettings>,
        providerId: String,
        providerName: String,
        range: PaceRange?,
    ): Map<String, SourceDownloadSettings> {
        val next = overrides.toMutableMap()
        next.remove(providerName)
        if (range == null) {
            next.remove(providerId)
        } else {
            next[providerId] = SourceDownloadSettings(concurrency = 1, delay = range.minMs, delayMax = maxOf(range.minMs, range.maxMs))
        }
        return next
    }

    /**
     * The delay steppers' upper limit: [STEPPER_MAX_MS], or a larger delay already saved (older
     * versions allowed any value), so the page shows that value as is instead of clamping it.
     */
    fun stepperMaxMs(range: PaceRange): Long = maxOf(STEPPER_MAX_MS, range.minMs, range.maxMs)

    fun matchingPreset(range: PaceRange): PacePreset? = presets.firstOrNull { it.range == range }

    /** 800 → "0.8", 2000 → "2", 1250 → "1.25". */
    fun seconds(ms: Long): String =
        BigDecimal(ms)
            .divide(BigDecimal(MS_PER_SECOND), 2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    /** "0.8–2.5 s", or "2 s" when both ends match. */
    fun rangeLabel(range: PaceRange): String =
        if (range.minMs == range.maxMs) "${seconds(range.minMs)} s" else "${seconds(range.minMs)}–${seconds(range.maxMs)} s"

    /** The per-site row's second line. */
    fun overrideSummary(override: SourceDownloadSettings?): String =
        override?.let { "${rangeLabel(PaceRange(it.delay, maxOf(it.delay, it.delayMax)))} delay" } ?: "Uses the defaults"

    /** The delay steppers' usual upper limit: two minutes between chapters. */
    const val STEPPER_MAX_MS = 120_000L

    private const val MS_PER_SECOND = 1_000
}
