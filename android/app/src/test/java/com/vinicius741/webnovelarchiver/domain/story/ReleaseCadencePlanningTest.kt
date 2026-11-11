package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning.Forecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCadencePlanningTest {
    private val weekly = (0..5).map { it * 7 * DAY_MS }
    private val lastWeekly = weekly.last()

    @Test
    fun predictsNextReleaseFromTheTypicalGap() {
        val now = lastWeekly + 2 * DAY_MS

        val forecast = ReleaseCadencePlanning.forecast(weekly, checkedAt = now, now = now)

        assertEquals(Forecast.Upcoming(lastWeekly + 7 * DAY_MS, 7 * DAY_MS), forecast)
        assertEquals(
            "Next chapter expected in ~5 days · usually every ~7 days",
            ReleaseCadencePlanning.describe(forecast!!, now),
        )
    }

    @Test
    fun flagsLateOnlyOnceTheLastCheckIsPastTheUsualVariation() {
        val now = lastWeekly + 12 * DAY_MS

        val late = ReleaseCadencePlanning.forecast(weekly, checkedAt = now, now = now)
        val due = ReleaseCadencePlanning.forecast(weekly, checkedAt = lastWeekly + 8 * DAY_MS, now = now)
        val maybeOut = ReleaseCadencePlanning.forecast(weekly, checkedAt = lastWeekly + 6 * DAY_MS, now = now)

        assertEquals(Forecast.Late(lastWeekly, 7 * DAY_MS), late)
        assertEquals(
            "Later than usual · no chapter in ~12 days · usually every ~7 days",
            ReleaseCadencePlanning.describe(late!!, now),
        )
        assertTrue(due is Forecast.Due)
        assertEquals("Next chapter due now · usually every ~7 days", ReleaseCadencePlanning.describe(due!!, now))
        assertTrue(maybeOut is Forecast.MaybeOut)
        assertEquals("Next chapter may be out · sync to check", ReleaseCadencePlanning.describe(maybeOut!!, now))
    }

    @Test
    fun longSilencesReadInMonthsAndYears() {
        val months = Forecast.Late(lastWeekly, 7 * DAY_MS)

        assertEquals(
            "Later than usual · no chapter in ~3 months · usually every ~7 days",
            ReleaseCadencePlanning.describe(months, lastWeekly + 90 * DAY_MS),
        )
        assertEquals(
            "Later than usual · no chapter in ~3 years · usually every ~7 days",
            ReleaseCadencePlanning.describe(months, lastWeekly + 1086 * DAY_MS),
        )
    }

    @Test
    fun dailyAuthorIsLateAfterAFullExtraDay() {
        val daily = (0..10).map { it * DAY_MS }
        val last = daily.last()

        assertTrue(ReleaseCadencePlanning.forecast(daily, last + 47 * HOUR_MS, last + 47 * HOUR_MS) is Forecast.Due)
        assertTrue(ReleaseCadencePlanning.forecast(daily, last + 49 * HOUR_MS, last + 49 * HOUR_MS) is Forecast.Late)
    }

    @Test
    fun chaptersPostedTogetherCountAsOneRelease() {
        val batched = weekly.flatMap { listOf(it, it + 10 * 60 * 1000L, it + 2 * HOUR_MS) }

        val forecast = ReleaseCadencePlanning.forecast(batched, checkedAt = lastWeekly, now = lastWeekly)

        assertEquals(7 * DAY_MS, forecast!!.typicalIntervalMs)
    }

    @Test
    fun oneOldHiatusDoesNotSkewTheTypicalGap() {
        val times = listOf(0L, 60 * DAY_MS) + (1..6).map { 60 * DAY_MS + it * 3 * DAY_MS }

        val forecast = ReleaseCadencePlanning.forecast(times, checkedAt = times.last(), now = times.last())

        assertEquals(3 * DAY_MS, forecast!!.typicalIntervalMs)
    }

    @Test
    fun needsAtLeastThreeGaps() {
        assertNull(ReleaseCadencePlanning.forecast(listOf(0L, 7 * DAY_MS, 14 * DAY_MS), 14 * DAY_MS, 14 * DAY_MS))
    }

    @Test
    fun storyForecastIgnoresPatreonCopiesAndSkipsUnusableStories() {
        val chapters = weekly.mapIndexed { i, t -> Chapter(id = "c$i", publishedAt = t) }.toMutableList()
        val patreon = Chapter(id = "p", publishedAt = lastWeekly + DAY_MS, patreonPostId = "1")
        val now = lastWeekly + 2 * DAY_MS
        val story = Story(chapters = (chapters + patreon).toMutableList(), lastChapterSyncAt = now)

        assertEquals(Forecast.Upcoming(lastWeekly + 7 * DAY_MS, 7 * DAY_MS), ReleaseCadencePlanning.forecast(story, now))
        assertNull(ReleaseCadencePlanning.forecast(story.copy(publicationStatus = PublicationStatus.completed), now))
        assertNull(ReleaseCadencePlanning.forecast(story.copy(isArchived = true), now))
        val unavailable = story.copy(sourceSyncState = SourceSyncState(availability = SourceAvailability.not_found))
        assertNull(ReleaseCadencePlanning.forecast(unavailable, now))
        // An undated newest chapter means the dates are incomplete.
        val undatedNewest = story.copy(chapters = (chapters + Chapter(id = "new")).toMutableList())
        assertNull(ReleaseCadencePlanning.forecast(undatedNewest, now))
    }

    @Test
    fun sporadicDatesOnlyCountTheUnbrokenNewestRun() {
        // FanFiction.net-style: past syncs left dates on chapters 1, 4, 7 and 10 only.
        val sporadic =
            (0..9).map { i -> Chapter(id = "c$i", publishedAt = if (i % 3 == 0) i * 7 * DAY_MS else null) }
        val now = 9 * 7 * DAY_MS + DAY_MS

        assertNull(ReleaseCadencePlanning.forecast(Story(chapters = sporadic.toMutableList(), lastChapterSyncAt = now), now))
    }

    @Test
    fun noSyncRecordNeverClaimsLate() {
        val chapters = weekly.mapIndexed { i, t -> Chapter(id = "c$i", publishedAt = t) }.toMutableList()
        val now = lastWeekly + 39 * DAY_MS
        // lastUpdated also moves on local downloads, so it is not evidence the source was checked.
        val story = Story(chapters = chapters, lastUpdated = now)

        assertTrue(ReleaseCadencePlanning.forecast(story, now) is Forecast.MaybeOut)
        assertTrue(ReleaseCadencePlanning.forecast(weekly, checkedAt = null, now = now) is Forecast.MaybeOut)
    }

    @Test
    fun libraryFlagsOnlyAReleaseThatCameDueAfterTheLastSync() {
        val chapters = weekly.mapIndexed { i, t -> Chapter(id = "c$i", publishedAt = t) }.toMutableList()
        val story = Story(chapters = chapters)
        val due = lastWeekly + 8 * DAY_MS

        // Synced before it came due: a sync may find the chapter.
        assertTrue(ReleaseCadencePlanning.mayHaveNewChapter(story.copy(lastChapterSyncAt = lastWeekly + DAY_MS), due))
        // Not due yet.
        assertFalse(ReleaseCadencePlanning.mayHaveNewChapter(story.copy(lastChapterSyncAt = lastWeekly + DAY_MS), lastWeekly + 3 * DAY_MS))
        // Synced after it came due and found nothing: already checked.
        assertFalse(ReleaseCadencePlanning.mayHaveNewChapter(story.copy(lastChapterSyncAt = due), due))
        // The author is late: the last sync was well past the usual gap.
        val lateCheck = lastWeekly + 20 * DAY_MS
        assertFalse(ReleaseCadencePlanning.mayHaveNewChapter(story.copy(lastChapterSyncAt = lateCheck), lateCheck))
    }

    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
