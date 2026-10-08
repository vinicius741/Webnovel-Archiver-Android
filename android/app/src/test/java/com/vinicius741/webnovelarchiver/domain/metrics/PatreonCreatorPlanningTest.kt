package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PatreonCreatorPlanningTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun creatorKeyIgnoresHostCasePrefixesAndTrailingPaths() {
        val expected = "creator"
        listOf(
            "https://www.patreon.com/Creator",
            "https://patreon.com/creator/",
            "https://www.patreon.com/c/Creator/posts",
            "https://www.patreon.com/cw/creator",
            "http://patreon.com/join/creator",
            "https://www.patreon.com/creator/about",
        ).forEach { url -> assertEquals(url, expected, PatreonCreatorPlanning.creatorKey(url)) }
    }

    @Test
    fun creatorKeyRejectsUrlsWithoutCreatorSlug() {
        assertNull(PatreonCreatorPlanning.creatorKey(null))
        assertNull(PatreonCreatorPlanning.creatorKey(""))
        assertNull(PatreonCreatorPlanning.creatorKey("https://www.patreon.com/user"))
        assertNull(PatreonCreatorPlanning.creatorKey("https://www.patreon.com/"))
        assertNull(PatreonCreatorPlanning.creatorKey("https://example.com/creator"))
    }

    @Test
    fun siblingsAreOtherLiveStoriesWithTheSameCreator() {
        val story = Story(id = "a", patreonUrl = "https://patreon.com/creator")
        val library =
            listOf(
                story,
                Story(id = "b", patreonUrl = "https://www.patreon.com/c/Creator"),
                Story(id = "archive", patreonUrl = "https://patreon.com/creator", isArchived = true),
                Story(id = "other", patreonUrl = "https://patreon.com/someone"),
                Story(id = "none"),
            )

        assertEquals(listOf("b"), PatreonCreatorPlanning.siblings(story, library).map { it.id })
        assertTrue(PatreonCreatorPlanning.siblings(Story(id = "none"), library).isEmpty())
    }

    @Test
    fun reconcileAdoptsTheFreshestStatsPerCreator() {
        val old = PatreonRawStats(capturedAt = 1L, paidMembers = 5)
        val fresh = PatreonRawStats(capturedAt = 9L, paidMembers = 7)
        val library =
            listOf(
                Story(id = "a", patreonUrl = "https://patreon.com/creator", patreonStats = old),
                Story(id = "b", patreonUrl = "https://patreon.com/c/creator", patreonStats = fresh),
                Story(id = "c", patreonUrl = "https://patreon.com/creator"),
                Story(id = "archive", patreonUrl = "https://patreon.com/creator", patreonStats = old, isArchived = true),
                Story(id = "solo", patreonUrl = "https://patreon.com/solo", patreonStats = old),
            )

        val changed = PatreonCreatorPlanning.reconcileLibrary(library)

        assertEquals(setOf("a", "c"), changed)
        assertEquals(listOf(fresh, fresh, fresh, old, old), library.map { it.patreonStats })
        assertTrue(PatreonCreatorPlanning.reconcileLibrary(library).isEmpty())
    }

    @Test
    fun mergedHistoryResolvesLaddersPerSourceAndKeepsLatestPerDay() {
        val ladderA = listOf(PatreonRawTier(usdCents = 300, members = 4))
        val ladderB = listOf(PatreonRawTier(usdCents = 500, members = 2))
        val a =
            StoryMetricHistory(
                storyId = "a",
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(
                            capturedAt = day(1),
                            score = "4.5",
                            patreonRaw = PatreonRawStats(capturedAt = day(1), paidMembers = 4, tiers = ladderA),
                        ),
                        StoryMetricSnapshot(capturedAt = day(2), score = "4.6"),
                        // Delta-encoded: carries ladderA from this file, never ladderB from the sibling.
                        StoryMetricSnapshot(capturedAt = day(4), patreonRaw = PatreonRawStats(capturedAt = day(4), paidMembers = 5)),
                    ),
            )
        val b =
            StoryMetricHistory(
                storyId = "b",
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(
                            capturedAt = day(1) + 1_000,
                            patreonRaw = PatreonRawStats(capturedAt = day(1) + 1_000, paidMembers = 6, tiers = ladderB),
                        ),
                        StoryMetricSnapshot(
                            capturedAt = day(3),
                            patreonRaw = PatreonRawStats(capturedAt = day(3), paidMembers = 6, tiers = ladderB),
                        ),
                    ),
            )

        val merged = PatreonCreatorPlanning.mergedPatreonHistory("a", listOf(a, b), zone)

        assertEquals("a", merged.storyId)
        assertEquals(listOf(day(1) + 1_000, day(3), day(4)), merged.snapshots.map { it.capturedAt })
        assertEquals(listOf(ladderB, ladderB, ladderA), merged.snapshots.map { it.patreonRaw?.tiers })
        assertTrue(merged.snapshots.all { it.score == null })
        assertEquals(
            listOf(6.0, 6.0, 5.0),
            MetricSnapshotPlanning.patreonSeries(merged, MetricSnapshotPlanning.PatreonField.MEMBERS).map { it.second },
        )
    }

    private fun day(n: Int): Long = n * 24L * 60 * 60 * 1000
}
