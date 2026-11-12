package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning.Forecast
import java.util.Calendar
import java.util.TimeZone

/**
 * Groups the library for the Updates destination from data the app already has: each novel's
 * release forecast ([ReleaseCadencePlanning]), its last chapter sync, and its downloaded chapters.
 *
 * "May be out" holds novels whose next chapter came due after the last check ([Forecast.MaybeOut]),
 * plus due novels ([Forecast.Due]) once enough time has passed since that check to make another one
 * worthwhile ([recheckAfterMs]). A sync that finds nothing therefore takes a novel off the list for a
 * while instead of leaving it there until it turns late.
 */
object UpdatesPlanning {
    enum class Section {
        MAY_BE_OUT,
        TO_DOWNLOAD,
        EXPECTED_SOON,
        RUNNING_LATE,
        RECENTLY_SYNCED,
    }

    data class Entry(
        val story: Story,
        val forecast: Forecast?,
        val missingChapters: Int,
    )

    /** One day of the release calendar; [startsAt] is local midnight. */
    data class CalendarDay(
        val startsAt: Long,
        val count: Int,
        val isToday: Boolean,
    )

    data class Overview(
        val sections: Map<Section, List<Entry>>,
        val calendar: List<CalendarDay>,
        /** Novels whose next chapter is expected within [SOON_SUMMARY_MS]. */
        val expectedWithinTwoDays: Int,
        /** Chapters known but not downloaded, across [Section.TO_DOWNLOAD]. */
        val missingChapters: Int,
    ) {
        fun entries(section: Section): List<Entry> = sections[section].orEmpty()

        fun count(section: Section): Int = entries(section).size
    }

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    const val EXPECTED_WINDOW_MS = 7L * DAY_MS
    const val SOON_SUMMARY_MS = 2L * DAY_MS
    const val RECENT_SYNC_WINDOW_MS = 7L * DAY_MS
    const val RECENT_SYNC_LIMIT = 50
    const val CALENDAR_DAYS = 7
    private const val MIN_RECHECK_MS = HOUR_MS
    private const val MAX_RECHECK_MS = 12L * HOUR_MS
    private const val RECHECK_FRACTION = 4L

    fun overview(
        stories: List<Story>,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Overview {
        val live = stories.filter(StoryActionGuards::canModifyStory)
        val entries = live.map { Entry(it, ReleaseCadencePlanning.forecast(it, now), missingChapters(it)) }
        val mayBeOut =
            entries
                .filter { mayBeOut(it.forecast, it.story.lastChapterSyncAt, now) }
                .sortedWith(compareBy<Entry> { if (it.forecast is Forecast.MaybeOut) 0 else 1 }.thenBy { expectedAt(it.forecast) })
        val toDownload =
            entries
                .filter { it.missingChapters > 0 && StoryActionGuards.canQueueDownloads(it.story) }
                .sortedWith(compareByDescending<Entry> { it.story.lastChapterSyncAt ?: 0L }.thenBy { it.story.title.lowercase() })
        val upcoming = entries.filter { it.forecast is Forecast.Upcoming }
        val expectedSoon =
            upcoming
                .filter { expectedAt(it.forecast) - now <= EXPECTED_WINDOW_MS }
                .sortedBy { expectedAt(it.forecast) }
        val late =
            entries
                .filter { it.forecast is Forecast.Late }
                .sortedByDescending { (it.forecast as Forecast.Late).lastReleaseAt }
        val synced =
            entries
                .filter { entry -> entry.story.lastChapterSyncAt?.let { now - it <= RECENT_SYNC_WINDOW_MS } == true }
                .sortedByDescending { it.story.lastChapterSyncAt }
                .take(RECENT_SYNC_LIMIT)
        return Overview(
            sections =
                linkedMapOf(
                    Section.MAY_BE_OUT to mayBeOut,
                    Section.TO_DOWNLOAD to toDownload,
                    Section.EXPECTED_SOON to expectedSoon,
                    Section.RUNNING_LATE to late,
                    Section.RECENTLY_SYNCED to synced,
                ),
            calendar = calendar(mayBeOut.size, upcoming.map { expectedAt(it.forecast) }, now, timeZone),
            expectedWithinTwoDays = upcoming.count { expectedAt(it.forecast) - now <= SOON_SUMMARY_MS },
            missingChapters = toDownload.sumOf { it.missingChapters },
        )
    }

    /** How many novels may have a new chapter out: the navigation badge on Updates. */
    fun badgeCount(
        stories: List<Story>,
        now: Long,
    ): Int =
        stories.count { story ->
            StoryActionGuards.canModifyStory(story) &&
                mayBeOut(ReleaseCadencePlanning.forecast(story, now), story.lastChapterSyncAt, now)
        }

    /** Chapters known to the app but not downloaded, counted like the Details "Download remaining" action. */
    fun missingChapters(story: Story): Int = story.chapters.count { !it.downloaded }

    /** Chapter positions the "To download" actions queue: every chapter not downloaded yet. */
    fun missingChapterIndexes(story: Story): List<Int> =
        story.chapters.mapIndexedNotNull { index, chapter -> index.takeIf { !chapter.downloaded } }

    fun mayBeOut(
        forecast: Forecast?,
        checkedAt: Long?,
        now: Long,
    ): Boolean =
        when (forecast) {
            is Forecast.MaybeOut -> true
            is Forecast.Due -> checkedAt == null || now - checkedAt >= recheckAfterMs(forecast.typicalIntervalMs)
            else -> false
        }

    /** A due novel is worth checking again after a quarter of its usual gap, between 1 and 12 hours. */
    fun recheckAfterMs(typicalIntervalMs: Long): Long = (typicalIntervalMs / RECHECK_FRACTION).coerceIn(MIN_RECHECK_MS, MAX_RECHECK_MS)

    /** Seven local days from today: novels that may be out now count today, then each predicted release on its day. */
    internal fun calendar(
        outNow: Int,
        expectedTimes: List<Long>,
        now: Long,
        timeZone: TimeZone,
    ): List<CalendarDay> {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val starts =
            (0..CALENDAR_DAYS).map { offset ->
                val day = cal.clone() as Calendar
                day.add(Calendar.DAY_OF_MONTH, offset)
                day.timeInMillis
            }
        return (0 until CALENDAR_DAYS).map { index ->
            val inDay = expectedTimes.count { it >= starts[index] && it < starts[index + 1] }
            CalendarDay(starts[index], inDay + if (index == 0) outNow else 0, isToday = index == 0)
        }
    }

    private fun expectedAt(forecast: Forecast?): Long =
        when (forecast) {
            is Forecast.Upcoming -> forecast.expectedAt
            is Forecast.MaybeOut -> forecast.expectedAt
            is Forecast.Due -> forecast.expectedAt
            is Forecast.Late -> forecast.lastReleaseAt
            null -> Long.MAX_VALUE
        }
}

/** Plain-language copy for the Updates rows and the "Sync all" result. */
object UpdatesCopy {
    /** One plain-language status line for a row in [section]. */
    fun statusLine(
        entry: UpdatesPlanning.Entry,
        section: UpdatesPlanning.Section,
        now: Long,
    ): String =
        when (section) {
            UpdatesPlanning.Section.TO_DOWNLOAD -> chaptersNotDownloaded(entry.missingChapters)
            UpdatesPlanning.Section.RECENTLY_SYNCED -> {
                val chapters = entry.story.chapters.size
                val synced = entry.story.lastChapterSyncAt?.let { "Synced ${ago(now - it)}" } ?: "Not synced yet"
                "$synced · $chapters ${if (chapters == 1) "chapter" else "chapters"}"
            }
            else -> entry.forecast?.let { ReleaseCadencePlanning.describe(it, now) } ?: "No release pattern yet"
        }

    /** One line after a "Sync all" run, e.g. "Found 5 new chapters in 2 novels · 1 couldn't be checked". */
    fun batchSummary(
        total: Int,
        checked: Int,
        newChapters: Int,
        novelsWithNew: Int,
        failed: Int,
        stopped: Boolean,
    ): String =
        buildList {
            if (stopped) add("Stopped after $checked of $total")
            add(
                if (newChapters >
                    0
                ) {
                    "Found ${plural(newChapters, "new chapter")} in ${plural(novelsWithNew, "novel")}"
                } else {
                    "No new chapters"
                },
            )
            if (failed > 0) add("$failed couldn't be checked")
        }.joinToString(" · ")

    fun newChaptersFound(
        count: Int,
        title: String,
    ): String = "Found ${plural(count, "new chapter")} in $title"

    private fun plural(
        count: Int,
        noun: String,
    ): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    fun chaptersNotDownloaded(count: Int): String = if (count == 1) "1 chapter not downloaded" else "$count chapters not downloaded"

    /** Short relative age: "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(elapsedMs: Long): String {
        val minutes = elapsedMs.coerceAtLeast(0L) / (60L * 1000L)
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 24L * 60L -> "${minutes / 60L} h ago"
            minutes < 48L * 60L -> "yesterday"
            else -> "${minutes / (24L * 60L)} days ago"
        }
    }
}
