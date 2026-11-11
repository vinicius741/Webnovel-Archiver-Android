package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Predicts a story's next public chapter from its recent per-chapter release dates.
 *
 * Chapters posted within [BATCH_WINDOW_MS] of each other count as one release, so an author who
 * drops three chapters at once reads as a single release, not two zero-length gaps. The typical
 * gap is the median of the last [MAX_INTERVALS] gaps, so one old hiatus does not skew it.
 * Only the newest run of consecutively dated chapters is used, so sources that date chapters
 * sporadically (FanFiction.net) rarely reach [MIN_INTERVALS].
 */
object ReleaseCadencePlanning {
    sealed interface Forecast {
        val typicalIntervalMs: Long

        /** The next chapter is not due yet. */
        data class Upcoming(
            val expectedAt: Long,
            override val typicalIntervalMs: Long,
        ) : Forecast

        /** It became due after the last check, so a new chapter may already be out. */
        data class MaybeOut(
            val expectedAt: Long,
            override val typicalIntervalMs: Long,
        ) : Forecast

        /** It is due, and the last check is still within the author's normal variation. */
        data class Due(
            val expectedAt: Long,
            override val typicalIntervalMs: Long,
        ) : Forecast

        /** The last check found nothing well past the usual gap. */
        data class Late(
            val lastReleaseAt: Long,
            override val typicalIntervalMs: Long,
        ) : Forecast
    }

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    const val BATCH_WINDOW_MS = 6L * HOUR_MS
    const val MIN_INTERVALS = 3
    const val MAX_INTERVALS = 10
    private const val LATE_FACTOR = 1.5
    private const val UPPER_QUARTILE = 0.75
    private const val MONTH_DAYS = 30L
    private const val YEAR_DAYS = 365L

    fun forecast(
        story: Story,
        now: Long = System.currentTimeMillis(),
    ): Forecast? {
        if (
            story.isArchived == true ||
            story.publicationStatus == PublicationStatus.completed ||
            story.sourceSyncState.availability != SourceAvailability.available
        ) {
            return null
        }
        val sourceChapters = story.chapters.filter { !it.isPatreonCopy }
        // An undated newest chapter means the dates are incomplete; the last dated one would read as overdue.
        if (sourceChapters.lastOrNull { !it.isRemovedFromSource }?.publishedAt == null) return null
        // Only the unbroken dated tail counts. A source that dates chapters sporadically (FanFiction.net
        // keeps the newest chapter's date from each past sync) would otherwise measure gaps that
        // span releases nobody saw.
        val datedTail =
            sourceChapters
                .asReversed()
                .asSequence()
                .map { it.publishedAt }
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
        // Only a chapter sync proves the source was checked; lastUpdated also moves on local downloads.
        return forecast(datedTail, story.lastChapterSyncAt?.coerceAtMost(now), now)
    }

    /**
     * True when the next chapter came due after the last chapter sync, so syncing would likely find
     * it. The Library flags these; a known-late author (checked past the usual gap) is not flagged.
     */
    fun mayHaveNewChapter(
        story: Story,
        now: Long = System.currentTimeMillis(),
    ): Boolean = forecast(story, now) is Forecast.MaybeOut

    /** [checkedAt] is the last source check, or null when there is no record of one. */
    fun forecast(
        releaseTimes: List<Long>,
        checkedAt: Long?,
        now: Long,
    ): Forecast? {
        val releases = releaseEvents(releaseTimes)
        val gaps = releases.zipWithNext { a, b -> b - a }.takeLast(MAX_INTERVALS)
        if (gaps.size < MIN_INTERVALS) return null
        val sorted = gaps.sorted()
        val typical = median(sorted)
        if (typical <= 0L) return null
        val upper = sorted[ceil(UPPER_QUARTILE * sorted.size).toInt() - 1]
        val lastRelease = releases.last()
        val expectedAt = lastRelease + typical
        val lateAt = lastRelease + maxOf(typical + DAY_MS, (typical * LATE_FACTOR).roundToLong(), upper)
        return when {
            now < expectedAt -> Forecast.Upcoming(expectedAt, typical)
            checkedAt == null || checkedAt < expectedAt -> Forecast.MaybeOut(expectedAt, typical)
            checkedAt >= lateAt -> Forecast.Late(lastRelease, typical)
            else -> Forecast.Due(expectedAt, typical)
        }
    }

    /** One short line for the Details header. */
    fun describe(
        forecast: Forecast,
        now: Long,
    ): String {
        val usually = "usually ${cadence(forecast.typicalIntervalMs)}"
        return when (forecast) {
            is Forecast.Upcoming -> "Next chapter expected in ${duration(forecast.expectedAt - now)} · $usually"
            is Forecast.Due -> "Next chapter due now · $usually"
            is Forecast.MaybeOut -> "Next chapter may be out · sync to check"
            is Forecast.Late -> "Later than usual · no chapter in ${duration(now - forecast.lastReleaseAt)} · $usually"
        }
    }

    /** Sorted release times with same-batch chapters merged into their batch's latest time. */
    internal fun releaseEvents(releaseTimes: List<Long>): List<Long> {
        val events = mutableListOf<Long>()
        releaseTimes.filter { it > 0L }.sorted().forEach { time ->
            if (events.isNotEmpty() && time - events.last() <= BATCH_WINDOW_MS) {
                events[events.lastIndex] = time
            } else {
                events += time
            }
        }
        return events
    }

    private fun median(sorted: List<Long>): Long {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun duration(ms: Long): String {
        val hours = max(1L, (ms.toDouble() / HOUR_MS).roundToLong())
        if (hours < 24L) return if (hours == 1L) "~1 hour" else "~$hours hours"
        val days = max(1L, (ms.toDouble() / DAY_MS).roundToLong())
        return when {
            days == 1L -> "~1 day"
            days < MONTH_DAYS * 2 -> "~$days days"
            days < YEAR_DAYS * 2 -> "~${(days.toDouble() / MONTH_DAYS).roundToLong()} months"
            else -> "~${(days.toDouble() / YEAR_DAYS).roundToLong()} years"
        }
    }

    private fun cadence(ms: Long): String {
        val hours = max(1L, (ms.toDouble() / HOUR_MS).roundToLong())
        if (hours < 20L) return "every ~$hours hours"
        val days = max(1L, (ms.toDouble() / DAY_MS).roundToLong())
        return if (days == 1L) "daily" else "every ~$days days"
    }
}
