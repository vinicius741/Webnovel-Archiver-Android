package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning.Forecast
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class UpdatesPlanningTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // Midday, so a day's worth of offsets stays on predictable calendar days.
    private val now = 100 * DAY_MS + 12 * HOUR_MS

    /** Releases every [gapMs] ending at [lastRelease]; [downloaded] chapters from the start are on the device. */
    private fun story(
        id: String,
        lastRelease: Long,
        gapMs: Long = 2 * DAY_MS,
        checkedAt: Long? = null,
        downloaded: Int = 6,
        status: PublicationStatus = PublicationStatus.ongoing,
    ): Story {
        val chapters =
            (0..5)
                .map { i -> Chapter(id = "$id-c$i", publishedAt = lastRelease - (5 - i) * gapMs, downloaded = i < downloaded) }
                .toMutableList()
        return Story(id = id, title = id, chapters = chapters, lastChapterSyncAt = checkedAt, publicationStatus = status)
    }

    @Test
    fun groupsNovelsByForecast() {
        // Due 6 h ago, last checked before that: may be out.
        val maybeOut = story("maybe", lastRelease = now - 2 * DAY_MS - 6 * HOUR_MS, checkedAt = now - DAY_MS)
        // Next chapter in 3 h.
        val soon = story("soon", lastRelease = now - 2 * DAY_MS + 3 * HOUR_MS, checkedAt = now)
        // Next chapter in 5 days, still within the week.
        val later = story("later", lastRelease = now - 2 * DAY_MS, gapMs = 7 * DAY_MS, checkedAt = now)
        // Checked well past the usual gap: running late.
        val late = story("late", lastRelease = now - 10 * DAY_MS, checkedAt = now - HOUR_MS)
        val completed = story("done", lastRelease = now - 10 * DAY_MS, status = PublicationStatus.completed)

        val overview = UpdatesPlanning.overview(listOf(late, later, soon, maybeOut, completed), now, utc)

        assertEquals(listOf("maybe"), overview.entries(Section.MAY_BE_OUT).map { it.story.id })
        assertEquals(listOf("soon", "later"), overview.entries(Section.EXPECTED_SOON).map { it.story.id })
        assertEquals(listOf("late"), overview.entries(Section.RUNNING_LATE).map { it.story.id })
        assertEquals(1, UpdatesPlanning.badgeCount(listOf(late, later, soon, maybeOut, completed), now))
    }

    @Test
    fun aDueNovelLeavesMayBeOutRightAfterACheckAndReturnsLater() {
        // Due 10 h ago; checked 1 h ago (after the due time) and nothing turned up.
        val justChecked = story("due", lastRelease = now - 2 * DAY_MS - 10 * HOUR_MS, checkedAt = now - HOUR_MS)
        val forecast = ReleaseCadencePlanning.forecast(justChecked, now)
        assertTrue(forecast is Forecast.Due)

        assertFalse(UpdatesPlanning.mayBeOut(forecast, justChecked.lastChapterSyncAt, now))
        // A quarter of the two-day gap (12 h) later it is worth checking again.
        val later = now + 12 * HOUR_MS
        assertTrue(UpdatesPlanning.mayBeOut(ReleaseCadencePlanning.forecast(justChecked, later), justChecked.lastChapterSyncAt, later))
    }

    @Test
    fun recheckWaitStaysBetweenOneAndTwelveHours() {
        assertEquals(HOUR_MS, UpdatesPlanning.recheckAfterMs(2 * HOUR_MS))
        assertEquals(6 * HOUR_MS, UpdatesPlanning.recheckAfterMs(DAY_MS))
        assertEquals(12 * HOUR_MS, UpdatesPlanning.recheckAfterMs(30 * DAY_MS))
    }

    @Test
    fun mayBeOutListsUncheckedNovelsFirstThenTheMostOverdue() {
        val dueLongAgo = story("old", lastRelease = now - 4 * DAY_MS, checkedAt = now - 3 * DAY_MS)
        val dueRecently = story("recent", lastRelease = now - 2 * DAY_MS - HOUR_MS, checkedAt = now - DAY_MS)
        // Checked after it came due (14 h ago), long enough ago to check again.
        val checkedDue = story("checked", lastRelease = now - 2 * DAY_MS - 20 * HOUR_MS, checkedAt = now - 14 * HOUR_MS)

        val ids =
            UpdatesPlanning
                .overview(listOf(checkedDue, dueRecently, dueLongAgo), now, utc)
                .entries(Section.MAY_BE_OUT)
                .map { it.story.id }

        assertEquals(listOf("old", "recent", "checked"), ids)
    }

    @Test
    fun toDownloadCountsChaptersNotOnTheDevice() {
        val partial = story("partial", lastRelease = now - DAY_MS, checkedAt = now - HOUR_MS, downloaded = 2)
        val complete = story("complete", lastRelease = now - DAY_MS, checkedAt = now)

        val overview = UpdatesPlanning.overview(listOf(complete, partial), now, utc)

        assertEquals(listOf("partial"), overview.entries(Section.TO_DOWNLOAD).map { it.story.id })
        assertEquals(4, overview.missingChapters)
        assertEquals(listOf(2, 3, 4, 5), UpdatesPlanning.missingChapterIndexes(partial))
        assertEquals(
            "4 chapters not downloaded",
            UpdatesCopy.statusLine(overview.entries(Section.TO_DOWNLOAD).single(), Section.TO_DOWNLOAD, now),
        )
    }

    @Test
    fun archivedSnapshotsAreLeftOut() {
        val archived = story("snap", lastRelease = now - 4 * DAY_MS, downloaded = 1).copy(isArchived = true)

        val overview = UpdatesPlanning.overview(listOf(archived), now, utc)

        assertTrue(overview.sections.values.all { it.isEmpty() })
        assertEquals(0, UpdatesPlanning.badgeCount(listOf(archived), now))
    }

    @Test
    fun recentlySyncedIsNewestFirstWithinAWeek() {
        val a = story("a", lastRelease = now - DAY_MS, checkedAt = now - 3 * HOUR_MS)
        val b = story("b", lastRelease = now - DAY_MS, checkedAt = now - 10 * 60 * 1000L)
        val stale = story("stale", lastRelease = now - DAY_MS, checkedAt = now - 8 * DAY_MS)

        val synced = UpdatesPlanning.overview(listOf(a, stale, b), now, utc).entries(Section.RECENTLY_SYNCED)

        assertEquals(listOf("b", "a"), synced.map { it.story.id })
        assertEquals("Synced 10 min ago · 6 chapters", UpdatesCopy.statusLine(synced.first(), Section.RECENTLY_SYNCED, now))
    }

    @Test
    fun calendarCountsOutNowTodayAndEachReleaseOnItsDay() {
        val todayStart = 100 * DAY_MS
        val days =
            UpdatesPlanning.calendar(
                outNow = 2,
                expectedTimes =
                    listOf(
                        todayStart + 20 * HOUR_MS,
                        todayStart + DAY_MS + HOUR_MS,
                        todayStart + 6 * DAY_MS,
                        todayStart + 7 * DAY_MS,
                    ),
                now = now,
                timeZone = utc,
            )

        assertEquals(7, days.size)
        assertEquals(todayStart, days.first().startsAt)
        assertTrue(days.first().isToday)
        assertEquals(listOf(3, 1, 0, 0, 0, 0, 1), days.map { it.count })
    }

    @Test
    fun expectedSoonSummaryCountsTheNextTwoDays() {
        val inOneDay = story("one", lastRelease = now - DAY_MS, checkedAt = now)
        val inFiveDays = story("five", lastRelease = now - 2 * DAY_MS, gapMs = 7 * DAY_MS, checkedAt = now)

        val overview = UpdatesPlanning.overview(listOf(inOneDay, inFiveDays), now, utc)

        assertEquals(1, overview.expectedWithinTwoDays)
        assertEquals(2, overview.count(Section.EXPECTED_SOON))
    }

    @Test
    fun batchSummaryReadsPlainly() {
        assertEquals("No new chapters", UpdatesCopy.batchSummary(3, 3, 0, 0, 0, stopped = false))
        assertEquals("Found 1 new chapter in 1 novel", UpdatesCopy.batchSummary(3, 3, 1, 1, 0, stopped = false))
        assertEquals(
            "Stopped after 2 of 9 · Found 5 new chapters in 2 novels · 1 couldn't be checked",
            UpdatesCopy.batchSummary(9, 2, 5, 2, 1, stopped = true),
        )
    }

    @Test
    fun aDeferredReviewKeepsOnlyChaptersStillMissing() {
        val partial = story("partial", lastRelease = now - DAY_MS, checkedAt = now - HOUR_MS, downloaded = 3)
        // c1 was downloaded meanwhile, c9 left the story; c3 and c5 still wait.
        assertEquals(
            listOf(3, 5),
            UpdatesPlanning.pendingReviewIndexes(partial, listOf("partial-c1", "partial-c3", "partial-c5", "partial-c9")),
        )
        assertEquals(emptyList<Int>(), UpdatesPlanning.pendingReviewIndexes(partial, listOf("partial-c0")))
    }

    @Test
    fun pendingReviewsReadPlainly() {
        assertEquals("1 large update to review", UpdatesCopy.pendingReviews(1, blockedSource = false))
        assertEquals("2 large updates to review · A source needs a check", UpdatesCopy.pendingReviews(2, blockedSource = true))
        assertEquals("A source needs a check", UpdatesCopy.pendingReviews(0, blockedSource = true))
    }

    @Test
    fun agesReadPlainly() {
        assertEquals("just now", UpdatesCopy.ago(20_000L))
        assertEquals("5 min ago", UpdatesCopy.ago(5 * 60_000L))
        assertEquals("3 h ago", UpdatesCopy.ago(3 * HOUR_MS))
        assertEquals("yesterday", UpdatesCopy.ago(30 * HOUR_MS))
        assertEquals("4 days ago", UpdatesCopy.ago(4 * DAY_MS))
    }

    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
