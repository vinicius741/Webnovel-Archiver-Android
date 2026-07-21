package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonStats
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MetricSnapshotPlanningTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun fromStoryCopiesScoreChaptersAndStatusAlways() {
        val story =
            Story(
                id = "s1",
                score = "4.84 / 5",
                totalChapters = 120,
                publicationStatus = PublicationStatus.ongoing,
            )
        val snap = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = false, capturedAt = 1_000L)
        assertEquals(1_000L, snap.capturedAt)
        assertEquals("4.84 / 5", snap.score)
        assertEquals(120, snap.totalChapters)
        assertEquals(PublicationStatus.ongoing, snap.publicationStatus)
        // Patreon not refreshed -> all Patreon fields null even if the story has stats.
        assertNull(snap.patreonPaidMembers)
        assertNull(snap.patreonMonthlyUsdCents)
        assertFalse(snap.patreonAmountIsEstimated)
        assertFalse(snap.patreonMembersIsEstimated)
    }

    @Test
    fun fromStoryCapturesPatreonOnlyWhenRefreshed() {
        val story =
            Story(
                id = "s1",
                patreonStats =
                    PatreonStats(
                        paidMembers = 410,
                        monthlyUsdCents = 5_500_00,
                        amountIsEstimated = true,
                        membersIsEstimated = false,
                    ),
            )
        val refreshed = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = true, capturedAt = 5L)
        assertEquals(410, refreshed.patreonPaidMembers)
        assertEquals(5_500_00L, refreshed.patreonMonthlyUsdCents)
        assertTrue(refreshed.patreonAmountIsEstimated)
        assertFalse(refreshed.patreonMembersIsEstimated)

        val notRefreshed = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = false, capturedAt = 5L)
        // The story HAS patreon stats, but they were carried forward, not refreshed this sync -> null.
        assertNull(notRefreshed.patreonPaidMembers)
        assertNull(notRefreshed.patreonMonthlyUsdCents)
    }

    @Test
    fun fromStoryWithNoPatreonStatsLeavesPatreonNullEvenWhenRefreshed() {
        val story = Story(id = "s1") // no patreonUrl, no patreonStats
        val snap = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = true)
        assertNull(snap.patreonPaidMembers)
        assertNull(snap.patreonMonthlyUsdCents)
    }

    @Test
    fun appendAndRetainCoalescesSameDayKeepingLatest() {
        val day = LocalDate.of(2026, 7, 17)
        val t0 =
            day
                .atStartOfDay(zone)
                .plusHours(9)
                .toInstant()
                .toEpochMilli()
        val t1 = t0 + 60_000 // one minute later, same calendar day
        val existing = listOf(snapshot(t0, score = "4.0"))
        val incoming = snapshot(t1, score = "4.5")
        val result = MetricSnapshotPlanning.appendAndRetain(existing, incoming, now = t1, zone = zone)
        assertEquals(1, result.size)
        assertEquals("4.5", result.single().score)
    }

    @Test
    fun appendAndRetainKeepsDistinctDaysOrderedOldestFirst() {
        val d1 = dayMillis(2026, 7, 15)
        val d2 = dayMillis(2026, 7, 16)
        val d3 = dayMillis(2026, 7, 17)
        val existing = listOf(snapshot(d1, "4.0"), snapshot(d2, "4.1"))
        val result =
            MetricSnapshotPlanning.appendAndRetain(existing, snapshot(d3, "4.2"), now = d3, zone = zone)
        assertEquals(listOf(d1, d2, d3), result.map { it.capturedAt })
    }

    @Test
    fun appendAndRetainDownsamplesOldTailUnderCap() {
        val now = dayMillis(2026, 7, 17)
        val dayMs = 24L * 60 * 60 * 1000
        val recentCutoffMillis = now - MetricSnapshotPlanning.RECENT_WINDOW_DAYS * dayMs
        // Recent points span distinct calendar days (one per day) inside the window, so they survive
        // same-day coalescing and each counts as its own day toward retention.
        val recent =
            (0 until 10).map { offset ->
                snapshot(recentCutoffMillis + offset * dayMs, "4.$offset")
            }
        val old =
            buildList {
                for (i in 0 until 1500) {
                    // well before the recent window, one per day going back in time
                    add(snapshot(recentCutoffMillis - (i + 1) * dayMs, "3.$i"))
                }
            }
        val existing = old + recent
        val incoming = snapshot(now, "4.9")
        val result = MetricSnapshotPlanning.appendAndRetain(existing, incoming, now = now, zone = zone)
        assertTrue("must not exceed cap", result.size <= MetricSnapshotPlanning.MAX_SNAPSHOTS)
        // Every recent point survives because it is inside the window and under the cap headroom.
        recent.forEach { snap ->
            assertTrue(
                "recent point ${snap.capturedAt} should be retained",
                result.any { it.capturedAt == snap.capturedAt },
            )
        }
        // The incoming point is also retained (it is the latest day).
        assertTrue(result.any { it.capturedAt == now })
        // Result is sorted oldest-first.
        val times = result.map { it.capturedAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun appendAndRetainKeepsEverythingUnderCap() {
        val base = dayMillis(2026, 7, 1)
        val existing = (0 until 50).map { i -> snapshot(base + i * 60_000, "4.$i") }
        val result =
            MetricSnapshotPlanning.appendAndRetain(existing, snapshot(base + 99 * 60_000, "5.0"), now = base + 100 * 60_000, zone = zone)
        // All within a few minutes -> same calendar day -> coalesced down to a single latest point.
        assertEquals(1, result.size)
    }

    @Test
    fun scoreSeriesSkipsNullAndUnparseableScores() {
        val history =
            StoryMetricHistory(
                snapshots =
                    mutableListOf(
                        snapshot(1L, "4.0"),
                        snapshot(2L, null),
                        snapshot(3L, "not a number"),
                        snapshot(4L, "4.5 / 5"),
                    ),
            )
        val series = MetricSnapshotPlanning.scoreSeries(history)
        assertEquals(listOf(1L to 4.0, 4L to 4.5), series)
    }

    @Test
    fun patreonSeriesSkipsUnmeasuredSnapshots() {
        val history =
            StoryMetricHistory(
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(capturedAt = 1L, patreonPaidMembers = 100, patreonMonthlyUsdCents = 1_000_00),
                        StoryMetricSnapshot(capturedAt = 2L), // batch sync: Patreon not measured
                        StoryMetricSnapshot(capturedAt = 3L, patreonPaidMembers = 110, patreonMonthlyUsdCents = 1_200_00),
                    ),
            )
        assertEquals(
            listOf(1L to 100.0, 3L to 110.0),
            MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MEMBERS),
        )
        // 1_000_00 cents == 100000 cents; the series exposes raw cents, not dollars.
        assertEquals(
            listOf(1L to 100_000.0, 3L to 120_000.0),
            MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MONTHLY_USD),
        )
    }

    @Test
    fun deltaIsNullWithFewerThanTwoPoints() {
        assertNull(MetricSnapshotPlanning.delta(emptyList()))
        assertNull(MetricSnapshotPlanning.delta(listOf(1L to 4.0)))
        assertEquals(0.1, MetricSnapshotPlanning.delta(listOf(1L to 4.0, 2L to 4.1))!!, 1e-9)
        assertEquals(-0.2, MetricSnapshotPlanning.delta(listOf(1L to 4.5, 2L to 4.3))!!, 1e-9)
    }

    /**
     * The USD series stores raw cents, so [MetricSnapshotPlanning.delta] returns the difference in
     * cents — the Trends screen must convert that to dollars before showing it (otherwise a $125
     * change reads as +12500). This pins the contract: callers cannot assume the delta is in display
     * units for the USD series.
     */
    @Test
    fun deltaOnUsdSeriesReturnsRawCentsDifference() {
        // $5,500 -> $5,625 (550000 -> 562500 cents)
        val usdPoints = listOf(1L to 550_000.0, 2L to 562_500.0)
        assertEquals(12_500.0, MetricSnapshotPlanning.delta(usdPoints)!!, 1e-9)
        // The members series is unaffected: raw count == display unit.
        val memberPoints = listOf(1L to 100.0, 2L to 440.0)
        assertEquals(340.0, MetricSnapshotPlanning.delta(memberPoints)!!, 1e-9)
    }

    @Test
    fun summaryReportsCountExtremaAndAverage() {
        val points = listOf(1L to 4.0, 2L to 4.5, 3L to 4.2)
        val s = MetricSnapshotPlanning.summary(points)
        assertEquals(3, s.count)
        assertEquals(4.0, s.min!!, 1e-9)
        assertEquals(4.5, s.max!!, 1e-9)
        assertEquals(4.0, s.first!!, 1e-9)
        assertEquals(4.2, s.last!!, 1e-9)
        assertEquals(4.233, s.average!!, 1e-3)
    }

    @Test
    fun summaryIsEmptyForEmptySeries() {
        val s = MetricSnapshotPlanning.summary(emptyList())
        assertEquals(0, s.count)
        assertNull(s.min)
        assertNull(s.lastAt)
    }

    @Test
    fun formatDeltaFormatsScoreAndWholeNumbers() {
        assertEquals("+0.12", MetricSnapshotPlanning.formatDelta(listOf(1L to 4.0, 2L to 4.12), asScore = true))
        assertEquals("-0.05", MetricSnapshotPlanning.formatDelta(listOf(1L to 4.1, 2L to 4.05), asScore = true))
        assertEquals("+0.00", MetricSnapshotPlanning.formatDelta(listOf(1L to 4.0, 2L to 4.0), asScore = true))
        assertNull(MetricSnapshotPlanning.formatDelta(listOf(1L to 4.0), asScore = true))
        // Whole-number path (members / cents): positive gains a '+'.
        assertEquals("+340", MetricSnapshotPlanning.formatDelta(listOf(1L to 100.0, 2L to 440.0), asScore = false))
        assertEquals("-340", MetricSnapshotPlanning.formatDelta(listOf(1L to 440.0, 2L to 100.0), asScore = false))
        assertEquals("0", MetricSnapshotPlanning.formatDelta(listOf(1L to 100.0, 2L to 100.4), asScore = false))
    }

    @Test
    fun isFlatDetectsNoMovement() {
        assertTrue(MetricSnapshotPlanning.isFlat(listOf(1L to 4.0, 2L to 4.0, 3L to 4.0)))
        assertFalse(MetricSnapshotPlanning.isFlat(listOf(1L to 4.0, 2L to 4.5)))
        assertFalse(MetricSnapshotPlanning.isFlat(listOf(1L to 4.0)))
    }

    @Test
    fun directionIsNullWithFewerThanTwoPoints() {
        assertNull(MetricSnapshotPlanning.direction(emptyList()))
        assertNull(MetricSnapshotPlanning.direction(listOf(1L to 4.0)))
    }

    @Test
    fun directionReadsOverallTrajectoryNotLastSync() {
        assertEquals(
            MetricSnapshotPlanning.TrendDirection.UP,
            MetricSnapshotPlanning.direction(listOf(1L to 4.0, 2L to 4.5, 3L to 4.4)),
        )
        assertEquals(
            MetricSnapshotPlanning.TrendDirection.DOWN,
            MetricSnapshotPlanning.direction(listOf(1L to 4.5, 2L to 4.0, 3L to 4.1)),
        )
        assertEquals(
            MetricSnapshotPlanning.TrendDirection.FLAT,
            MetricSnapshotPlanning.direction(listOf(1L to 4.0, 2L to 4.6, 3L to 4.0)),
        )
    }

    private fun dayMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private fun snapshot(
        at: Long,
        score: String?,
    ): StoryMetricSnapshot = StoryMetricSnapshot(capturedAt = at, score = score)
}
