package com.vinicius741.webnovelarchiver.source

import java.time.ZoneId

/** Normalizes visible source labels before matching them across markup variants. */
internal fun normalizedSourceText(value: String?): String =
    value
        .orEmpty()
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Parses source counters such as `1,240`, `2K`, or `2.36M` into a stable persisted integer. The
 * source label remains represented by [com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind].
 */
internal fun parseSourceMetricValue(value: String?): Long? {
    val normalized = normalizedSourceText(value).replace(",", "").replace("_", "")
    val match = Regex("(?i)([-+]?\\d+(?:\\.\\d+)?)\\s*([KMB])?").find(normalized) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier =
        when (match.groupValues[2].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
    return (number * multiplier).toLong()
}

/**
 * Parses a source-authored timestamp with the same tolerant rules used for chapter dates. Naive
 * dates/datetimes are interpreted in [ZoneId.systemDefault] so they stay consistent with the
 * chapter-date helpers in [SourceProvider]; epoch-based inputs are zone-independent.
 */
internal fun parseSourceDateMillis(
    value: String?,
    now: Long = System.currentTimeMillis(),
): Long? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.let { parseSourceDateAtZone(it, now, ZoneId.systemDefault()) }

private fun parseSourceDateAtZone(
    value: String,
    now: Long,
    zone: ZoneId,
): Long? {
    val normalized =
        value
            .replace(Regex("""\b(\d{1,2})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("""^\s*[A-Za-z]+,\s+"""), "")
            .trim()
    parseEpochMillis(normalized)?.let { return it }
    parseRelativeTime(normalized, now)?.let { return it }
    parseInstantMillis(normalized)?.let { return it }
    localDateTimeFormatters
        .firstNotNullOfOrNull { formatter ->
            runCatching {
                java.time.LocalDateTime
                    .parse(normalized, formatter)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }?.let { return it }
    localDateFormatters
        .firstNotNullOfOrNull { formatter ->
            runCatching {
                java.time.LocalDate
                    .parse(normalized, formatter)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }?.let { return it }
    return listOf(
        Regex("""(?i)\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}\b"""),
        Regex("""(?i)\b\d{1,2}(?:st|nd|rd|th)?\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{4}\b"""),
    ).firstNotNullOfOrNull { pattern ->
        pattern
            .find(normalized)
            ?.value
            ?.takeIf { it != normalized }
            ?.let { parseSourceDateAtZone(it, now, zone) }
    }
}
