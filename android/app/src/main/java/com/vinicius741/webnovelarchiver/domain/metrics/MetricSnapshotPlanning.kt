package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** A single chart point: epoch-millis X, numeric Y. Declared at top level (Kotlin disallows nested typealias). */
typealias MetricPoint = Pair<Long, Double>

/**
 * Pure planning for the per-novel metric-history feature.
 *
 * Every deterministic decision lives here so the storage/sync/UI layers stay thin and the rules are
 * unit-testable without Android or I/O:
 *  - [fromStory] turns a freshly-synced [Story] into the snapshot to record.
 *  - [appendAndRetain] coalesces same-day points and downsamples old history so each story's history
 *    file stays bounded.
 *  - the series/summary helpers project a history into the `(capturedAt, value)` points a chart needs.
 *
 * Rules are intentionally calendar-day (not 24h-window) based so a user re-syncing the same novel
 * several times in a day replaces that day's point instead of stacking near-duplicates — the trend
 * is "what the metric looked like per day", not "every individual sync".
 */
object MetricSnapshotPlanning {
    /** Snapshots newer than this many days are kept at full (per-day) resolution. */
    const val RECENT_WINDOW_DAYS = 60L

    /** Hard cap on retained snapshots per story, enforced after coalescing + downsampling. */
    const val MAX_SNAPSHOTS = 1000

    /**
     * Builds the snapshot to record for [story]. [patreonRefreshed] must be true only when the sync
     * actually fetched fresh Patreon figures this run — `false` on batch "Follow Updates" syncs and
     * on stories with no Patreon URL. When false the Patreon fields stay `null` so a chart reads the
     * gap as "not measured" rather than "zero".
     */
    fun fromStory(
        story: Story,
        patreonRefreshed: Boolean,
        capturedAt: Long = System.currentTimeMillis(),
    ): StoryMetricSnapshot {
        val patreon = story.patreonStats?.takeIf { patreonRefreshed }
        return StoryMetricSnapshot(
            capturedAt = capturedAt,
            score = story.score,
            totalChapters = story.totalChapters,
            publicationStatus = story.publicationStatus,
            patreonPaidMembers = patreon?.paidMembers,
            patreonMonthlyUsdCents = patreon?.monthlyUsdCents,
            patreonAmountIsEstimated = patreon?.amountIsEstimated ?: false,
            patreonMembersIsEstimated = patreon?.membersIsEstimated ?: false,
        )
    }

    /**
     * Appends [incoming] to [existing] and applies retention. Same-calendar-day coalescing keeps
     * only the latest snapshot per (ZoneId) day — so re-syncing today replaces today's point. Beyond
     * [RECENT_WINDOW_DAYS] the result is downsampled to one point per day (the latest of each day),
     * then capped at [MAX_SNAPSHOTS] by dropping the oldest. The returned list is ordered oldest-first
     * to match how charts and summaries expect to iterate.
     *
     * [now] and [zone] are parameters (not read from the system) so the retention math is testable
     * against fixed clocks.
     */
    fun appendAndRetain(
        existing: List<StoryMetricSnapshot>,
        incoming: StoryMetricSnapshot,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StoryMetricSnapshot> {
        val withIncoming = (existing + incoming).sortedBy { it.capturedAt }
        // First pass: keep the latest snapshot for each calendar day. A user syncing twice in a day
        // expects today's trend point to move, not to see two near-identical dots.
        val perDay = LinkedHashMap<LocalDate, StoryMetricSnapshot>()
        for (snapshot in withIncoming) {
            val day = Instant.ofEpochMilli(snapshot.capturedAt).atZone(zone).toLocalDate()
            perDay[day] = snapshot // later entries (same day) overwrite earlier ones
        }
        val coalesced = perDay.values.sortedBy { it.capturedAt }
        if (coalesced.size <= MAX_SNAPSHOTS) return coalesced

        // Over the cap: prefer keeping every day inside the recent window, and for the remaining
        // (older) tail drop the oldest days until back under the cap. This keeps recent resolution
        // intact and degrades gracefully on very long histories.
        val recentCutoff = now - RECENT_WINDOW_DAYS * MILLIS_PER_DAY
        val recent = coalesced.filter { it.capturedAt >= recentCutoff }
        val old = coalesced.filter { it.capturedAt < recentCutoff }
        val keepFromOld = (MAX_SNAPSHOTS - recent.size).coerceAtLeast(0)
        val trimmedOld = old.takeLast(keepFromOld)
        return (trimmedOld + recent).sortedBy { it.capturedAt }
    }

    /** Which Patreon series a caller is interested in; see [patreonSeries]. */
    enum class PatreonField { MEMBERS, MONTHLY_USD }

    /** Pulls the parsed score from every snapshot that has a parseable score, oldest-first. */
    fun scoreSeries(history: StoryMetricHistory): List<MetricPoint> =
        history.snapshots
            .mapNotNull { snap -> parseScore(snap.score)?.let { snap.capturedAt to it } }

    /** Pulls the requested Patreon field, skipping snapshots where it wasn't measured. */
    fun patreonSeries(
        history: StoryMetricHistory,
        field: PatreonField,
    ): List<MetricPoint> =
        history.snapshots.mapNotNull { snap ->
            when (field) {
                PatreonField.MEMBERS -> snap.patreonPaidMembers?.toDouble()?.let { snap.capturedAt to it }
                PatreonField.MONTHLY_USD -> snap.patreonMonthlyUsdCents?.toDouble()?.let { snap.capturedAt to it }
            }
        }

    /** Last value minus the previous value, or `null` when there are fewer than two points. */
    fun delta(points: List<MetricPoint>): Double? = if (points.size < 2) null else points.last().second - points.dropLast(1).last().second

    /** Overall direction of movement across a series; see [direction]. */
    enum class TrendDirection { UP, DOWN, FLAT }

    /**
     * Direction of movement across the whole series (last value minus first), used by the compact
     * trend indicator on the Details screen. Unlike [delta] (last sync only) this reads the overall
     * trajectory since recording started, so one noisy sync cannot flip the indicator. `null` when
     * there are fewer than two points — nothing meaningful to show yet.
     */
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

    /**
     * Formats a signed delta for a summary line (e.g. `"+0.12"`, `"-340"`, `"0.00"`). For score the
     * value is shown to two decimals; otherwise it is rounded to a whole number (members / cents).
     * Returns `null` when there is no delta (fewer than two points).
     */
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

    // Mirrors the HostUi.formatScore extraction so a snapshot's raw score ("4.8", "4.84 / 5") is
    // reduced to the same numeric value that is displayed elsewhere. Kept here (not imported from
    // the ui package) so the planning module stays free of Android dependencies.
    private fun parseScore(score: String?): Double? {
        val raw = score?.takeIf { it.isNotBlank() } ?: return null
        return Regex("""(\d+(?:\.\d+)?)""")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    }
}
