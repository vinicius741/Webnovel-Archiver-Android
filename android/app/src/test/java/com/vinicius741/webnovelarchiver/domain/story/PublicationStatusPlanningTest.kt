package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicationStatusPlanningTest {
    @Test
    fun effectiveStatusMarksOngoingStoryOutdatedAfterSevenDaysWithoutChapterSync() {
        val now = 10L * DAY_MS
        val story =
            Story(
                publicationStatus = PublicationStatus.ongoing,
                lastChapterSyncAt = now - PublicationStatusPlanning.OUTDATED_AFTER_MS - 1L,
            )

        assertEquals(PublicationStatus.outdated, PublicationStatusPlanning.effectiveStatus(story, now))
    }

    @Test
    fun effectiveStatusDoesNotMarkCompletedOrHiatusStoriesOutdated() {
        val now = 10L * DAY_MS
        val oldSync = now - PublicationStatusPlanning.OUTDATED_AFTER_MS - 1L

        assertEquals(
            PublicationStatus.completed,
            PublicationStatusPlanning.effectiveStatus(
                Story(publicationStatus = PublicationStatus.completed, lastChapterSyncAt = oldSync),
                now,
            ),
        )
        assertEquals(
            PublicationStatus.hiatus,
            PublicationStatusPlanning.effectiveStatus(
                Story(publicationStatus = PublicationStatus.hiatus, lastChapterSyncAt = oldSync),
                now,
            ),
        )
    }

    @Test
    fun afterSyncMarksHiatusWhenLatestPublishedChapterIsOlderThanThirtyDays() {
        val syncedAt = 60L * DAY_MS
        val latestPublishedAt = syncedAt - PublicationStatusPlanning.HIATUS_AFTER_MS - 1L

        assertEquals(
            PublicationStatus.hiatus,
            PublicationStatusPlanning.afterSync(PublicationStatus.ongoing, latestPublishedAt, syncedAt),
        )
    }

    @Test
    fun afterSyncNeverOverridesCompletedWithHiatus() {
        val syncedAt = 60L * DAY_MS
        val latestPublishedAt = syncedAt - PublicationStatusPlanning.HIATUS_AFTER_MS - 1L

        assertEquals(
            PublicationStatus.completed,
            PublicationStatusPlanning.afterSync(PublicationStatus.completed, latestPublishedAt, syncedAt),
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
