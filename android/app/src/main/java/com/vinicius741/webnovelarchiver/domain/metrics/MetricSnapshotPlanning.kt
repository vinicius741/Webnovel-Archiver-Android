package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Chart point, epoch-millis X and numeric Y. Top-level because Kotlin disallows nested typealiases. */
typealias MetricPoint = Pair<Long, Double>

/**
 * Pure planning for the metric-history feature, unit-testable without Android or I/O.
 * Retention is calendar-day based, not a 24h window, so re-syncing a novel several times in one
 * day replaces that day's point instead of stacking near-duplicates.
 */
object MetricSnapshotPlanning {
    /** Snapshots newer than this many days are kept at full (per-day) resolution. */
    const val RECENT_WINDOW_DAYS = 60L

    /** Hard cap on retained snapshots per story, enforced after coalescing + downsampling. */
    const val MAX_SNAPSHOTS = 1000

    /**
     * [patreonRefreshed] must be true only when the sync fetched fresh Patreon figures this run.
     * When false [StoryMetricSnapshot.patreonRaw] stays null so a chart reads the gap as not
     * measured, not zero.
     */
    fun fromStory(
        story: Story,
        patreonRefreshed: Boolean,
        capturedAt: Long = System.currentTimeMillis(),
    ): StoryMetricSnapshot =
        StoryMetricSnapshot(
            capturedAt = capturedAt,
            score = story.score,
            totalChapters = story.totalChapters,
            publicationStatus = story.publicationStatus,
            patreonRaw = story.patreonStats?.takeIf { patreonRefreshed },
            metrics = story.sourceMetadata.metrics.toMutableList(),
        )

    /**
     * Appends [incoming] and retains: keep the latest snapshot per calendar day, then cap at
     * [MAX_SNAPSHOTS] by dropping the oldest days outside the recent window first. Result is
     * oldest-first. An incoming Patreon tier ladder identical to the previous explicit one is
     * delta-encoded to null (carry-forward) so history files stay small; [patreonSeries] resolves
     * it back. The anchor must live on a strictly earlier day — same-day re-syncs keep the ladder
     * explicit, because last-wins coalescing would otherwise replace the anchor with the encoded
     * snapshot and orphan the whole day. [now] and [zone] are injected so the retention math is
     * testable.
     */
    fun appendAndRetain(
        existing: List<StoryMetricSnapshot>,
        incoming: StoryMetricSnapshot,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StoryMetricSnapshot> {
        val dayStart =
            Instant
                .ofEpochMilli(incoming.capturedAt)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val anchor = existing.lastOrNull { it.capturedAt < dayStart && it.patreonRaw?.tiers != null }?.patreonRaw?.tiers
        val encoded =
            incoming.copy(
                patreonRaw =
                    incoming.patreonRaw?.let { raw ->
                        if (raw.tiers != null && raw.tiers == anchor) raw.copy(tiers = null) else raw
                    },
            )
        val withIncoming = (existing + encoded).sortedBy { it.capturedAt }
        // Keep the latest snapshot per calendar day; re-syncing replaces today's point.
        val perDay = LinkedHashMap<LocalDate, StoryMetricSnapshot>()
        for (snapshot in withIncoming) {
            val day = Instant.ofEpochMilli(snapshot.capturedAt).atZone(zone).toLocalDate()
            perDay[day] = snapshot
        }
        val coalesced = perDay.values.sortedBy { it.capturedAt }
        if (coalesced.size <= MAX_SNAPSHOTS) return coalesced

        // Over the cap: keep the recent window intact, drop the oldest days from the older tail.
        val recentCutoff = now - RECENT_WINDOW_DAYS * MILLIS_PER_DAY
        val recent = coalesced.filter { it.capturedAt >= recentCutoff }
        val old = coalesced.filter { it.capturedAt < recentCutoff }
        val keepFromOld = (MAX_SNAPSHOTS - recent.size).coerceAtLeast(0)
        val kept = (old.takeLast(keepFromOld) + recent).sortedBy { it.capturedAt }
        // Trimming drops oldest days first — possibly the day holding the only explicit ladder the
        // surviving null-tier snapshots carry. Materialize that ladder into the first survivor so
        // the kept tail keeps resolving without its original anchor.
        val firstCarried = kept.indexOfFirst { it.patreonRaw != null && it.patreonRaw?.tiers == null }
        if (firstCarried < 0) return kept
        val carriedAt = kept[firstCarried].capturedAt
        val ladder =
            coalesced.lastOrNull { it.capturedAt < carriedAt && it.patreonRaw?.tiers != null }?.patreonRaw?.tiers
                ?: return kept
        return kept.toMutableList().also { list ->
            list[firstCarried] =
                list[firstCarried].copy(patreonRaw = list[firstCarried].patreonRaw?.copy(tiers = ladder))
        }
    }

    enum class PatreonField { MEMBERS, MONTHLY_USD }

    /** Pulls the parsed score from every snapshot that has a parseable score, oldest-first. */
    fun scoreSeries(history: StoryMetricHistory): List<MetricPoint> =
        history.snapshots.mapNotNull { snap ->
            // Same numeric reduction HostUi.formatScore applies, so chart values match displayed ones.
            SCORE_REGEX
                .find(snap.score.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?.let { snap.capturedAt to it }
        }

    /**
     * Pulls the requested Patreon field, skipping snapshots where it wasn't measured. Null tier
     * ladders inherit the most recent explicit ladder (delta encoding); the dollar series is
     * re-derived from raw inputs so formula changes apply to the whole history.
     */
    fun patreonSeries(
        history: StoryMetricHistory,
        field: PatreonField,
    ): List<MetricPoint> {
        var carriedTiers: List<PatreonRawTier>? = null
        return history.snapshots.mapNotNull { snap ->
            val raw = snap.patreonRaw ?: return@mapNotNull null
            raw.tiers?.let { carriedTiers = it }
            val earnings = PatreonEarningsPlanning.estimate(raw.copy(tiers = raw.tiers ?: carriedTiers)) ?: return@mapNotNull null
            when (field) {
                PatreonField.MEMBERS -> snap.capturedAt to earnings.paidMembers.toDouble()
                PatreonField.MONTHLY_USD ->
                    earnings.monthlyUsdCents?.toDouble()?.let { snap.capturedAt to it }
            }
        }
    }

    /** Pulls the requested source metric (watchers, favorites, …), skipping snapshots without it. */
    fun metricSeries(
        history: StoryMetricHistory,
        kind: SourceMetricKind,
    ): List<MetricPoint> =
        history.snapshots.mapNotNull { snap ->
            // orEmpty: pre-metrics JSON decoded through the Unsafe path could leave the list null.
            snap.metrics
                .orEmpty()
                .firstOrNull { it.kind == kind }
                ?.value
                ?.toDouble()
                ?.let { snap.capturedAt to it }
        }

    /** Last value minus the previous value, or `null` when there are fewer than two points. */
    fun delta(points: List<MetricPoint>): Double? = if (points.size < 2) null else points.last().second - points.dropLast(1).last().second

    enum class TrendDirection { UP, DOWN, FLAT }

    /** Whole-series direction, last minus first, so one noisy sync cannot flip the indicator. Null when fewer than two points. */
    fun direction(points: List<MetricPoint>): TrendDirection? {
        if (points.size < 2) return null
        val movement = points.last().second - points.first().second
        return when {
            movement > FLAT_EPSILON -> TrendDirection.UP
            movement < -FLAT_EPSILON -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }
    }

    /** Summary statistics over a series; `count == 0` when there are no points. */
    data class Summary(
        val count: Int,
        val firstAt: Long?,
        val lastAt: Long?,
        val first: Double?,
        val last: Double?,
        val min: Double?,
        val max: Double?,
        val average: Double?,
    )

    fun summary(points: List<MetricPoint>): Summary {
        if (points.isEmpty()) return Summary(0, null, null, null, null, null, null, null)
        val values = points.map { it.second }
        return Summary(
            count = points.size,
            firstAt = points.first().first,
            lastAt = points.last().first,
            first = values.first(),
            last = values.last(),
            min = values.min(),
            max = values.max(),
            average = values.average(),
        )
    }

    /** Signed delta for a summary line, two decimals for score and a whole number otherwise. Null when there is no delta. */
    fun formatDelta(
        points: List<MetricPoint>,
        asScore: Boolean,
    ): String? {
        val value = delta(points) ?: return null
        return if (asScore) {
            String.format(Locale.US, "%+.2f", value)
        } else {
            val rounded = value.toLong()
            if (rounded > 0) "+$rounded" else rounded.toString()
        }
    }

    /** True when the stored value is effectively unchanged across the series (no movement to chart). */
    fun isFlat(points: List<MetricPoint>): Boolean =
        points.size >= 2 && points.all { abs(it.second - points.first().second) < FLAT_EPSILON }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val FLAT_EPSILON = 1e-9

    // Leading numeric value of a raw score, e.g. "4.8" or "4.84 / 5".
    private val SCORE_REGEX = Regex("""(\d+(?:\.\d+)?)""")
}
