package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Ranks the library's novels by how fast their recorded statistics are rising right now, for the
 * Trending screen. Pure and deterministic: the clock comes in as `now`.
 *
 * How a novel's heat is built:
 * 1. Each signal (Patreon paid members, the source's audience count, total views, rating) yields
 *    its growth between consecutive snapshots: log growth for counts, smoothed by a per-signal
 *    constant so 2→5 members does not outrank 1,750→2,069, and the plain change for the rating.
 * 2. Growth fades with age, like Reddit's hot ranking: growth is spread evenly over the time
 *    between two snapshots and each day of it counts `0.5^(age / HALF_LIFE_DAYS)`. A burst is
 *    worth half as much six days later and nearly nothing after three weeks, so the list rotates
 *    without stored state, and a novel comes back as soon as a new burst is recorded.
 * 3. Steady growers are discounted: part of the growth the novel's own older rate predicts (or
 *    the library's typical rate, for novels with little history) is subtracted, so a burst beats
 *    a large novel that always grows.
 * 4. Each signal is scaled against the library's own 80th-percentile movement for that signal,
 *    so Patreon, followers and views compete on equal terms and no single source of numbers can
 *    fill the list. A novel's heat is its strongest signal plus a smaller share of the others.
 *
 * Novels the reader has caught up on (bookmark on the last chapter) are left out; Patreon growth
 * is credited once per creator, to that creator's hottest novel.
 */
object TrendingPlanning {
    /** Days after which a recorded rise counts half as much. */
    const val HALF_LIFE_DAYS = 6.0

    /** Snapshots older than this form a novel's baseline rate; newer ones are "recent". */
    const val RECENT_WINDOW_DAYS = 14L

    /** Days of history the sparkline shows. */
    const val SPARKLINE_DAYS = 45L

    /** Heat below this is noise, not a trend. */
    const val MIN_HEAT = 0.2

    const val MAX_ENTRIES = 30

    /** How much of the expected (baseline) growth is subtracted from the recent growth. */
    private const val BASELINE_DISCOUNT = 0.6

    /** Weight of a novel's own baseline against the library's typical rate. */
    private const val OWN_BASELINE_WEIGHT = 0.5

    /** A baseline needs at least this many days between its first and last snapshot. */
    private const val MIN_BASELINE_SPAN_DAYS = 10.0

    /** Share of each non-lead signal added to the lead signal's strength. */
    private const val SUPPORT_SHARE = 0.35

    /** Percentile of positive movements that maps to half strength. */
    private const val SCALE_PERCENTILE = 0.8

    /** Smallest rating rise (points, after decay) that reaches half strength. */
    private const val MIN_RATING_SCALE = 0.03

    /** Strength a supporting signal needs before the row mentions it. */
    const val MENTION_STRENGTH = 0.08

    /** Shortest gap between snapshots, in days, so two syncs minutes apart do not divide by ~0. */
    private const val MIN_GAP_DAYS = 0.25

    private const val MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000

    /** Audience counts in order of preference; the first one a novel records is its audience signal. */
    val AUDIENCE_KINDS: List<SourceMetricKind> =
        listOf(
            SourceMetricKind.FOLLOWERS,
            SourceMetricKind.WATCHERS,
            SourceMetricKind.FOLLOWS,
            SourceMetricKind.FAVORITES,
            SourceMetricKind.READERS,
            SourceMetricKind.BOOKMARKS,
            SourceMetricKind.KUDOS,
            SourceMetricKind.RATINGS,
            SourceMetricKind.REVIEWS,
            SourceMetricKind.LIKES,
        )

    private val VIEW_KINDS = listOf(SourceMetricKind.TOTAL_VIEWS, SourceMetricKind.TOTAL_VIEWS_CHAPTERS)

    /**
     * One kind of statistic. [smoothing] is added to both sides of a count's growth ratio, so small
     * absolute numbers cannot produce huge percentages; [relative] is false for the rating, whose
     * movement is its plain change.
     */
    enum class Signal(
        val weight: Double,
        val smoothing: Double,
        val relative: Boolean,
    ) {
        PATREON(1.0, 60.0, true),
        AUDIENCE(1.0, 100.0, true),
        VIEWS(0.6, 5000.0, true),
        RATING(0.6, 0.0, false),
    }

    /** One novel's inputs. [patreonHistory] is the creator-merged Patreon history ([PatreonCreatorPlanning]). */
    data class Candidate(
        val story: Story,
        val history: StoryMetricHistory,
        val patreonHistory: StoryMetricHistory = history,
        /** The Patreon creator, so novels sharing one creator share its Patreon growth once. */
        val creatorKey: String? = null,
    )

    /** One signal's movement for a novel. */
    data class Movement(
        val signal: Signal,
        /** What was measured: "Patrons" (paid members), "Watchers", "Total views", "Rating". */
        val label: String,
        /** The signal's last [SPARKLINE_DAYS] of points, oldest first. */
        val points: List<MetricPoint>,
        /** Latest value minus the value at the start of the recent window (or the first point). */
        val change: Double,
        /** Days [change] spans. */
        val changeDays: Double,
        val latest: Double,
        /** 0..[Signal.weight]: how this movement compares with the library's. */
        val strength: Double,
    )

    sealed interface Reading {
        /** No bookmark: [chapters] chapters to discover. */
        data class NotStarted(
            val chapters: Int,
        ) : Reading

        /** Bookmark set with [unread] chapters after it. */
        data class Behind(
            val unread: Int,
        ) : Reading
    }

    data class Entry(
        val story: Story,
        val heat: Double,
        val lead: Movement,
        /** Other rising signals worth mentioning, strongest first. */
        val supporting: List<Movement>,
        val reading: Reading,
    )

    data class Result(
        /** Hottest first, at most [MAX_ENTRIES]. */
        val entries: List<Entry>,
        /** Novels left out because the reader is on their last chapter. */
        val caughtUpHidden: Int,
        /** Novels with at least two snapshots of some signal, so they could trend. */
        val measured: Int,
    ) {
        fun count(signal: Signal): Int = entries.count { it.lead.signal == signal }
    }

    /** True when the bookmark sits on the novel's last chapter, so there is nothing new to read. */
    fun isCaughtUp(story: Story): Boolean {
        val last = story.chapters.lastOrNull() ?: return false
        return story.lastReadChapterId == last.id
    }

    fun reading(story: Story): Reading {
        val bookmark = story.lastReadChapterId ?: return Reading.NotStarted(story.chapters.size)
        val index = story.chapters.indexOfFirst { it.id == bookmark }
        if (index < 0) return Reading.NotStarted(story.chapters.size)
        return Reading.Behind(story.chapters.size - 1 - index)
    }

    fun plan(
        candidates: List<Candidate>,
        now: Long,
    ): Result {
        val eligible = candidates.filterNot { isCaughtUp(it.story) }
        val series = eligible.associate { it.story.id to extractSeries(it, now) }
        val typicalRates = typicalRates(series.values, now)
        val effective =
            series.mapValues { (_, bySignal) ->
                bySignal.mapValues { (signal, points) -> effectiveGrowth(signal, points, typicalRates[signal] ?: 0.0, now) }
            }
        val scales = Signal.entries.associateWith { signal -> scaleFor(signal, effective.values.mapNotNull { it[signal] }) }
        val scored =
            eligible.mapNotNull { candidate ->
                val bySignal = series[candidate.story.id].orEmpty()
                val strengths =
                    effective[candidate.story.id]
                        .orEmpty()
                        .mapValues { (signal, value) -> signal.weight * saturate(value, scales.getValue(signal)) }
                if (strengths.isEmpty()) null else Scored(candidate, bySignal, strengths)
            }
        val credited = creditPatreonOncePerCreator(scored)
        val entries =
            credited
                .mapNotNull { it.toEntry(now) }
                .filter { it.heat >= MIN_HEAT }
                .sortedWith(compareByDescending<Entry> { it.heat }.thenBy { it.story.title.lowercase() })
                .take(MAX_ENTRIES)
        return Result(
            entries = entries,
            caughtUpHidden = candidates.size - eligible.size,
            measured = series.count { (_, bySignal) -> bySignal.isNotEmpty() },
        )
    }

    private data class Scored(
        val candidate: Candidate,
        val series: Map<Signal, LabeledSeries>,
        val strengths: Map<Signal, Double>,
    ) {
        val heat: Double get() = heatOf(strengths)
    }

    private data class LabeledSeries(
        val label: String,
        val points: List<MetricPoint>,
    )

    private fun heatOf(strengths: Map<Signal, Double>): Double {
        val lead = strengths.values.maxOrNull() ?: return 0.0
        return lead + SUPPORT_SHARE * (strengths.values.sum() - lead)
    }

    /** Novels of one Patreon creator share one Patreon history; only the hottest of them keeps its growth. */
    private fun creditPatreonOncePerCreator(scored: List<Scored>): List<Scored> {
        val keepers =
            scored
                .filter { it.candidate.creatorKey != null && (it.strengths[Signal.PATREON] ?: 0.0) > 0.0 }
                .groupBy { it.candidate.creatorKey }
                .values
                .mapNotNull { group ->
                    group
                        .maxByOrNull { it.heat }
                        ?.candidate
                        ?.story
                        ?.id
                }.toSet()
        return scored.map { item ->
            val shared = item.candidate.creatorKey != null && Signal.PATREON in item.strengths
            if (!shared || item.candidate.story.id in keepers) item else item.copy(strengths = item.strengths - Signal.PATREON)
        }
    }

    /**
     * A signal counts only when it ends the recent window higher than it started: a recovery from a
     * dip has recent growth but is not a rise. Heat is recomputed from the signals that remain.
     */
    private fun Scored.toEntry(now: Long): Entry? {
        val rising =
            strengths
                .filterValues { it > 0.0 }
                .map { (signal, strength) -> movementOf(signal, series.getValue(signal), strength, now) }
                .filter { it.change > 0.0 }
                .sortedByDescending { it.strength }
        val lead = rising.firstOrNull() ?: return null
        return Entry(
            story = candidate.story,
            heat = heatOf(rising.associate { it.signal to it.strength }),
            lead = lead,
            supporting = rising.drop(1).filter { it.strength >= MENTION_STRENGTH },
            reading = reading(candidate.story),
        )
    }

    private fun movementOf(
        signal: Signal,
        series: LabeledSeries,
        strength: Double,
        now: Long,
    ): Movement {
        val points = series.points
        val latest = points.last()
        val windowStart = latest.first - RECENT_WINDOW_DAYS * MILLIS_PER_DAY
        val start = points.lastOrNull { it.first <= windowStart } ?: points.first()
        val sparkFrom = now - SPARKLINE_DAYS * MILLIS_PER_DAY
        // Keep the point just before the sparkline window so the line enters from the left edge.
        val sparkStart = (points.indexOfLast { it.first < sparkFrom }).coerceAtLeast(0)
        return Movement(
            signal = signal,
            label = series.label,
            points = points.drop(sparkStart),
            change = latest.second - start.second,
            changeDays = (latest.first - start.first) / MILLIS_PER_DAY,
            latest = latest.second,
            strength = strength,
        )
    }

    private fun extractSeries(
        candidate: Candidate,
        now: Long,
    ): Map<Signal, LabeledSeries> {
        val history = candidate.history.pastOnly(now)
        val result = LinkedHashMap<Signal, LabeledSeries>()
        val patreon =
            MetricSnapshotPlanning.patreonSeries(candidate.patreonHistory.pastOnly(now), MetricSnapshotPlanning.PatreonField.MEMBERS)
        if (patreon.size >= 2) result[Signal.PATREON] = LabeledSeries("Patrons", patreon)
        AUDIENCE_KINDS
            .firstNotNullOfOrNull { kind ->
                MetricSnapshotPlanning.metricSeries(history, kind).takeIf { it.size >= 2 }?.let { LabeledSeries(kind.label, it) }
            }?.let { result[Signal.AUDIENCE] = it }
        VIEW_KINDS
            .firstNotNullOfOrNull { kind ->
                MetricSnapshotPlanning.metricSeries(history, kind).takeIf { it.size >= 2 }?.let { LabeledSeries(kind.label, it) }
            }?.let { result[Signal.VIEWS] = it }
        val rating = MetricSnapshotPlanning.scoreSeries(history)
        if (rating.size >= 2) result[Signal.RATING] = LabeledSeries("Rating", rating)
        return result
    }

    private fun StoryMetricHistory.pastOnly(now: Long): StoryMetricHistory =
        if (snapshots.all { it.capturedAt <= now }) this else copy(snapshots = snapshots.filter { it.capturedAt <= now }.toMutableList())

    /** The library's median baseline rate per relative signal, the prior for novels without enough history. */
    private fun typicalRates(
        all: Collection<Map<Signal, LabeledSeries>>,
        now: Long,
    ): Map<Signal, Double> =
        Signal.entries.filter { it.relative }.associateWith { signal ->
            median(all.mapNotNull { it[signal]?.let { series -> ownBaselineRate(signal, series.points, now) } })
        }

    /**
     * Recent decayed growth minus part of the growth the baseline rate predicts over the same span.
     * The rating has no baseline: most ratings drift down as a novel ages, so any rise is news.
     */
    private fun effectiveGrowth(
        signal: Signal,
        series: LabeledSeries,
        typicalRate: Double,
        now: Long,
    ): Double {
        val points = series.points
        val recent = decayedGrowth(signal, points, now)
        if (!signal.relative) return recent
        val own = ownBaselineRate(signal, points, now)
        val rate = if (own == null) typicalRate else OWN_BASELINE_WEIGHT * own + (1 - OWN_BASELINE_WEIGHT) * typicalRate
        val expected = max(rate, 0.0) * decayIntegral(points.first().first, points.last().first, now)
        return recent - BASELINE_DISCOUNT * expected
    }

    /** Sum of each gap's growth, spread evenly over the gap and weighted by its age decay. */
    internal fun decayedGrowth(
        signal: Signal,
        points: List<MetricPoint>,
        now: Long,
    ): Double =
        points.zipWithNext().sumOf { (a, b) ->
            val days = max((b.first - a.first) / MILLIS_PER_DAY, MIN_GAP_DAYS)
            growth(signal, a.second, b.second) * decayIntegral(a.first, b.first, now) / days
        }

    /**
     * Growth per day before the recent window, or null without [MIN_BASELINE_SPAN_DAYS] of older
     * history.
     */
    private fun ownBaselineRate(
        signal: Signal,
        points: List<MetricPoint>,
        now: Long,
    ): Double? {
        val older = points.filter { it.first < now - RECENT_WINDOW_DAYS * MILLIS_PER_DAY }
        if (older.size < 2) return null
        val days = (older.last().first - older.first().first) / MILLIS_PER_DAY
        if (days < MIN_BASELINE_SPAN_DAYS) return null
        return growth(signal, older.first().second, older.last().second) / days
    }

    private fun growth(
        signal: Signal,
        from: Double,
        to: Double,
    ): Double =
        if (signal.relative) {
            val smoothedFrom = max(from, 0.0) + signal.smoothing
            val smoothedTo = max(to, 0.0) + signal.smoothing
            if (smoothedFrom <= 0.0 || smoothedTo <= 0.0) 0.0 else ln(smoothedTo / smoothedFrom)
        } else {
            to - from
        }

    /** ∫ 0.5^(age / HALF_LIFE_DAYS) d(days) between two instants, ages measured back from [now]. */
    internal fun decayIntegral(
        from: Long,
        to: Long,
        now: Long,
    ): Double {
        val ageFrom = max((now - from) / MILLIS_PER_DAY, 0.0)
        val ageTo = max((now - to) / MILLIS_PER_DAY, 0.0)
        return HALF_LIFE_DAYS / ln(2.0) * (0.5.pow(ageTo / HALF_LIFE_DAYS) - 0.5.pow(ageFrom / HALF_LIFE_DAYS))
    }

    /** The movement that maps to half strength: the library's [SCALE_PERCENTILE] of positive movements. */
    private fun scaleFor(
        signal: Signal,
        values: List<Double>,
    ): Double {
        val positive = values.filter { it > 0.0 }.sorted()
        val percentile =
            if (positive.isEmpty()) {
                0.0
            } else {
                positive[
                    (positive.size * SCALE_PERCENTILE).toInt().coerceAtMost(
                        positive.lastIndex,
                    ),
                ]
            }
        val floor = if (signal == Signal.RATING) MIN_RATING_SCALE else Double.MIN_VALUE
        return max(percentile, floor)
    }

    /** 0..1, half at [scale]; never quite 1, so the strongest movers stay ordered. */
    private fun saturate(
        value: Double,
        scale: Double,
    ): Double = if (value <= 0.0) 0.0 else value / (value + scale)

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
