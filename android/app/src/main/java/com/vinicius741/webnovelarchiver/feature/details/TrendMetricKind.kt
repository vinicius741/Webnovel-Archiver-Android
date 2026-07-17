package com.vinicius741.webnovelarchiver.feature.details

/**
 * Which metric a trend chart is rendering. Drives the Y-axis formatting and (for score) the fixed
 * 0–5 axis range, so each chart reads naturally in its own units. See [buildTrendChart].
 */
enum class TrendMetricKind {
    /** Novel rating. Y is the score (0–5), formatted to two decimals. */
    SCORE,

    /** Patreon paid-member count. Y is a count, formatted as a whole number. */
    PATREON_MEMBERS,

    /** Patreon monthly earnings. Y is in USD cents, formatted as compact currency. */
    PATREON_USD,
}
