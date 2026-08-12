package com.vinicius741.webnovelarchiver.source

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

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
                LocalDateTime
                    .parse(normalized, formatter)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }?.let { return it }
    localDateFormatters
        .firstNotNullOfOrNull { formatter ->
            runCatching {
                LocalDate
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

private fun parseEpochMillis(value: String): Long? {
    val numeric = Regex("""^\d{10,13}$""").find(value)?.value ?: return null
    val raw = numeric.toLongOrNull() ?: return null
    return if (numeric.length <= 10) raw * 1000L else raw
}

private fun parseRelativeTime(
    value: String,
    now: Long,
): Long? {
    val match =
        Regex("""(?i)\b(an?|one|\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago\b""")
            .find(value)
            ?: return null
    val amount =
        when (val raw = match.groupValues[1].lowercase()) {
            "a", "an", "one" -> 1L
            else -> raw.toLongOrNull() ?: return null
        }
    val unitMillis =
        when (match.groupValues[2].lowercase()) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            "year" -> 31_536_000_000L
            else -> return null
        }
    return now - amount * unitMillis
}

private fun parseInstantMillis(value: String): Long? =
    listOf<(String) -> Long>(
        { Instant.parse(it).toEpochMilli() },
        { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(it).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() },
    ).firstNotNullOfOrNull { parser -> runCatching { parser(value) }.getOrNull() }

private val localDateTimeFormatters =
    listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "MMM d, yyyy h:mm a",
        "MMMM d, yyyy h:mm a",
        "MMM d yyyy h:mm a",
        "MMMM d yyyy h:mm a",
    ).map(::sourceDateFormatter)

private val localDateFormatters =
    listOf(
        "yyyy-MM-dd",
        "MMM d, yyyy",
        "MMMM d, yyyy",
        "MMM d yyyy",
        "MMMM d yyyy",
        "d MMM yyyy",
        "d MMMM yyyy",
    ).map(::sourceDateFormatter)

private fun sourceDateFormatter(pattern: String): DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(Locale.US)
