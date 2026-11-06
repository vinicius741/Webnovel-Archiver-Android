package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun creatorKeyReadsNumericUserIds() {
        assertEquals("u:123", PatreonCreatorPlanning.creatorKey("https://www.patreon.com/user?u=123"))
        assertEquals("u:123", PatreonCreatorPlanning.creatorKey("https://www.patreon.com/user/posts?utm=x&u=123"))
        assertEquals("u:123", PatreonCreatorPlanning.creatorKey("https://www.patreon.com/profile/creators?u=123"))
    }

    @Test
    fun normalizeCreatorLinkAcceptsTheFormsPeoplePaste() {
        val expected = "https://www.patreon.com/Creator"
        listOf(
            "https://www.patreon.com/Creator",
            "  http://patreon.com/Creator/  ",
            "patreon.com/Creator",
            "www.patreon.com/c/Creator/posts",
            "https://www.patreon.com/cw/Creator?utm_source=share",
            "https://www.patreon.com/join/Creator",
            "HTTPS://WWW.PATREON.COM/Creator/about",
        ).forEach { input -> assertEquals(input, expected, PatreonCreatorPlanning.normalizeCreatorLink(input)) }
        assertEquals(
            "https://www.patreon.com/user?u=123",
            PatreonCreatorPlanning.normalizeCreatorLink("patreon.com/user?u=123&fan_landing=true"),
        )
    }

    @Test
    fun normalizeCreatorLinkRejectsAnythingElse() {
        listOf(
            "",
            "creator",
            "https://example.com/creator",
            "https://patreon.com.example.com/creator",
            "https://notpatreon.com/creator",
            "ftp://patreon.com/creator",
            "javascript:alert(1)",
            "https://www.patreon.com/",
            "https://www.patreon.com/home",
            "https://www.patreon.com/user",
            "https://www.patreon.com/posts/chapter-12-123456",
            "https://www.patreon.com/collection/2030713",
            "https://www.patreon.com/creator name",
        ).forEach { input -> assertNull(input, PatreonCreatorPlanning.normalizeCreatorLink(input)) }
    }

    @Test
    fun sourceLinkKeepsUserIdsAndDropsTrackingParameters() {
        assertEquals("https://www.patreon.com/user?u=123", PatreonCreatorPlanning.sourceLink("https://patreon.com/user?u=123"))
        assertEquals(
            "https://www.patreon.com/user?u=123",
            PatreonCreatorPlanning.sourceLink("https://www.patreon.com/user?utm=x&u=123#top"),
        )
        assertEquals(
            "https://patreon.com/creator/posts",
            PatreonCreatorPlanning.sourceLink("https://patreon.com/creator/posts?utm_source=rr"),
        )
        assertNull(PatreonCreatorPlanning.sourceLink("https://www.patreon.com/user"))
        assertNull(PatreonCreatorPlanning.sourceLink("https://www.patreon.com/user?utm_source=rr"))
    }

    @Test
    fun sameCreatorComparesIdentityNotSpelling() {
        assertTrue(PatreonCreatorPlanning.sameCreator("https://www.patreon.com/Creator", "https://patreon.com/c/creator/posts"))
        assertTrue(PatreonCreatorPlanning.sameCreator(null, null))
        assertFalse(PatreonCreatorPlanning.sameCreator("https://www.patreon.com/one", "https://www.patreon.com/two"))
        assertFalse(PatreonCreatorPlanning.sameCreator(null, "https://www.patreon.com/two"))
        assertFalse(PatreonCreatorPlanning.sameCreator("https://www.patreon.com/home", "https://www.patreon.com/login"))
    }

    @Test
    fun manualLinkDecidesWhichCreatorAStorySharesStatsWith() {
        val story = Story(id = "a", patreonUrl = "https://www.patreon.com/wrong", manualPatreonUrl = "https://www.patreon.com/right")
        val sameCreator = Story(id = "b", patreonUrl = "https://www.patreon.com/right")
        val sourceLinkOnly = Story(id = "c", patreonUrl = "https://www.patreon.com/wrong")

        assertEquals(listOf("b"), PatreonCreatorPlanning.siblings(story, listOf(story, sameCreator, sourceLinkOnly)).map { it.id })
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
