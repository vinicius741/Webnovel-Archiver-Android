package com.vinicius741.webnovelarchiver.domain.metrics

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
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
        // Patreon not refreshed -> raw block null even if the story has stats.
        assertNull(snap.patreonRaw)
    }

    @Test
    fun fromStoryCapturesPatreonOnlyWhenRefreshed() {
        val story =
            Story(
                id = "s1",
                patreonStats =
                    PatreonRawStats(
                        capturedAt = 4L,
                        paidMembers = 410,
                        tiers = listOf(PatreonRawTier(usdCents = 1_000)),
                    ),
            )
        val refreshed = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = true, capturedAt = 5L)
        assertEquals(story.patreonStats, refreshed.patreonRaw)

        val notRefreshed = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = false, capturedAt = 5L)
        // The story HAS patreon stats, but they were carried forward, not refreshed this sync -> null.
        assertNull(notRefreshed.patreonRaw)
    }

    @Test
    fun fromStoryWithNoPatreonStatsLeavesPatreonNullEvenWhenRefreshed() {
        val story = Story(id = "s1") // no patreonUrl, no patreonStats
        val snap = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = true)
        assertNull(snap.patreonRaw)
    }

    @Test
    fun fromStoryCopiesSourceMetricsOnEverySync() {
        // Unlike the Patreon fields, source metrics (watchers, favorites…) are captured on every
        // sync — they come from the story page parse itself, not a separate optional refresh.
        val story =
            Story(
                id = "sb1",
                sourceMetadata =
                    SourceMetadata(
                        metrics =
                            mutableListOf(
                                SourceMetric(SourceMetricKind.WATCHERS, 4_900),
                                SourceMetric(SourceMetricKind.REPLIES, 1_204),
                            ),
                    ),
            )
        val snap = MetricSnapshotPlanning.fromStory(story, patreonRefreshed = false, capturedAt = 9L)
        assertEquals(
            listOf(SourceMetric(SourceMetricKind.WATCHERS, 4_900), SourceMetric(SourceMetricKind.REPLIES, 1_204)),
            snap.metrics.toList(),
        )
    }

    @Test
    fun metricSeriesExtractsRequestedKindAndSkipsSnapshotsWithoutIt() {
        val history =
            StoryMetricHistory(
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(
                            capturedAt = 1L,
                            metrics =
                                mutableListOf(
                                    SourceMetric(SourceMetricKind.WATCHERS, 100),
                                    SourceMetric(SourceMetricKind.LIKES, 5),
                                ),
                        ),
                        StoryMetricSnapshot(capturedAt = 2L), // sync before the field existed
                        StoryMetricSnapshot(capturedAt = 3L, metrics = mutableListOf(SourceMetric(SourceMetricKind.WATCHERS, 112))),
                    ),
            )
        assertEquals(
            listOf(1L to 100.0, 3L to 112.0),
            MetricSnapshotPlanning.metricSeries(history, SourceMetricKind.WATCHERS),
        )
        // A kind the source never reported has no points — "missing", not "zero".
        assertEquals(
            emptyList<Pair<Long, Double>>(),
            MetricSnapshotPlanning.metricSeries(history, SourceMetricKind.FAVORITES),
        )
    }

    @Test
    fun legacySnapshotJsonWithoutMetricsRestoresWithEmptyList() {
        // History JSON written before the metrics field existed (every history recorded to date).
        // Gson applies the no-arg-ctor default for the absent key, so old files load without a
        // format migration and simply start accruing metric points on later syncs.
        val history =
            Gson().fromJson(
                """{"storyId":"rr_123","snapshots":[{"capturedAt":1000,"score":"4.5","totalChapters":10,"publicationStatus":"ongoing"}]}""",
                StoryMetricHistory::class.java,
            )
        assertEquals("rr_123", history.storyId)
        assertTrue(
            history.snapshots
                .single()
                .metrics
                .isEmpty(),
        )
    }

    @Test
    fun snapshotWithMetricsRoundTripsThroughGson() {
        val history =
            StoryMetricHistory(
                storyId = "sb_9",
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(
                            capturedAt = 5L,
                            metrics = mutableListOf(SourceMetric(SourceMetricKind.WATCHERS, 4_900, isEstimated = true)),
                        ),
                    ),
            )
        val restored = Gson().fromJson(Gson().toJson(history), StoryMetricHistory::class.java)
        assertEquals(history.snapshots, restored.snapshots)
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
                        StoryMetricSnapshot(
                            capturedAt = 1L,
                            patreonRaw = PatreonRawStats(paidMembers = 100, exactMonthlyUsdCents = 1_000_00),
                        ),
                        StoryMetricSnapshot(capturedAt = 2L), // batch sync: Patreon not measured
                        StoryMetricSnapshot(
                            capturedAt = 3L,
                            patreonRaw = PatreonRawStats(paidMembers = 110, exactMonthlyUsdCents = 1_200_00),
                        ),
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
    fun appendAndRetainKeepsLadderExplicitForSameDayResyncs() {
        // Same-day last-wins coalescing replaces the earlier snapshot, so a same-day anchor must not
        // be delta-encoded against — otherwise the day ends with tiers=null and no anchor anywhere.
        val ladder = listOf(PatreonRawTier(usdCents = 500), PatreonRawTier(usdCents = 1_500))
        val t0 = dayMillis(2026, 7, 17) + 9 * 3_600_000L
        val t1 = t0 + 60_000 // same calendar day
        val first =
            MetricSnapshotPlanning.appendAndRetain(
                emptyList(),
                StoryMetricSnapshot(capturedAt = t0, patreonRaw = PatreonRawStats(capturedAt = t0, paidMembers = 100, tiers = ladder)),
                now = t0,
                zone = zone,
            )
        val resynced =
            MetricSnapshotPlanning.appendAndRetain(
                first,
                StoryMetricSnapshot(capturedAt = t1, patreonRaw = PatreonRawStats(capturedAt = t1, paidMembers = 110, tiers = ladder)),
                now = t1,
                zone = zone,
            )
        assertEquals(1, resynced.size)
        assertEquals(110, resynced.single().patreonRaw?.paidMembers)
        assertEquals(ladder, resynced.single().patreonRaw?.tiers)
    }

    @Test
    fun appendAndRetainDeltaEncodesUnchangedTierLadderAcrossDays() {
        val ladder = listOf(PatreonRawTier(usdCents = 500), PatreonRawTier(usdCents = 1_500))
        val d0 = dayMillis(2026, 7, 16)
        val d1 = dayMillis(2026, 7, 17)
        val existing =
            listOf(
                StoryMetricSnapshot(capturedAt = d0, patreonRaw = PatreonRawStats(capturedAt = d0, paidMembers = 100, tiers = ladder)),
            )
        val nextDay =
            MetricSnapshotPlanning.appendAndRetain(
                existing,
                StoryMetricSnapshot(capturedAt = d1, patreonRaw = PatreonRawStats(capturedAt = d1, paidMembers = 110, tiers = ladder)),
                now = d1,
                zone = zone,
            )
        // Identical ladder on a later day -> stored as null (carry-forward); members stay fresh
        // and the previous day's explicit anchor survives.
        assertEquals(110, nextDay.last().patreonRaw?.paidMembers)
        assertNull(nextDay.last().patreonRaw?.tiers)
        assertEquals(ladder, nextDay.first().patreonRaw?.tiers)

        val changed = ladder + PatreonRawTier(usdCents = 5_000)
        val d2 = dayMillis(2026, 7, 18)
        val newLadder =
            MetricSnapshotPlanning.appendAndRetain(
                nextDay,
                StoryMetricSnapshot(capturedAt = d2, patreonRaw = PatreonRawStats(capturedAt = d2, paidMembers = 120, tiers = changed)),
                now = d2,
                zone = zone,
            )
        assertEquals(changed, newLadder.last().patreonRaw?.tiers)
    }

    @Test
    fun appendAndRetainReanchorsCarriedLadderWhenTrimDropsItsAnchor() {
        // Over-cap retention drops the oldest days — where the only explicit ladder sits. The first
        // surviving carry-forward snapshot must be materialized with that ladder so the kept tail
        // still resolves.
        val ladder = listOf(PatreonRawTier(usdCents = 1_000))
        val dayMs = 24L * 60 * 60 * 1000
        val base = dayMillis(2020, 1, 1)
        val day0 =
            StoryMetricSnapshot(capturedAt = base, patreonRaw = PatreonRawStats(capturedAt = base, paidMembers = 100, tiers = ladder))
        val carried =
            (1..1004).map { i ->
                StoryMetricSnapshot(
                    capturedAt = base + i * dayMs,
                    patreonRaw =
                        PatreonRawStats(
                            capturedAt = base + i * dayMs,
                            paidMembers =
                                100 + i,
                            tiers = null,
                        ),
                )
            }
        val incomingAt = base + 1005 * dayMs
        val result =
            MetricSnapshotPlanning.appendAndRetain(
                listOf(day0) + carried,
                StoryMetricSnapshot(
                    capturedAt = incomingAt,
                    patreonRaw = PatreonRawStats(capturedAt = incomingAt, paidMembers = 1105, tiers = null),
                ),
                now = incomingAt,
                zone = zone,
            )

        assertTrue(result.size <= MetricSnapshotPlanning.MAX_SNAPSHOTS)
        // The trimmed prefix held the anchor; the first survivor now carries it explicitly and the
        // rest still resolve through carry-forward.
        assertEquals(ladder, result.first().patreonRaw?.tiers)
        assertNull(result[1].patreonRaw?.tiers)
    }

    @Test
    fun patreonSeriesCarriesTierLadderForwardAndRecomputesEstimates() {
        // Snapshot 2 stored no ladder (delta-encoded); the USD series must resolve it from snapshot
        // 1 and derive both points with the current formula — the retroactive-recalculation contract.
        val ladder = listOf(PatreonRawTier(usdCents = 1_000), PatreonRawTier(usdCents = 3_000))
        val history =
            StoryMetricHistory(
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(capturedAt = 1L, patreonRaw = PatreonRawStats(paidMembers = 100, tiers = ladder)),
                        StoryMetricSnapshot(capturedAt = 2L, patreonRaw = PatreonRawStats(paidMembers = 200, tiers = null)),
                    ),
            )
        // Median 2000c × 200 members × 0.9 = 360000c.
        assertEquals(
            listOf(1L to 180_000.0, 2L to 360_000.0),
            MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MONTHLY_USD),
        )
    }

    @Test
    fun legacySnapshotJsonWithDerivedPatreonFieldsLoadsThemAsUnmeasured() {
        // History JSON written before raw storage: derived patreon fields are unknown keys now, so
        // old points read as "not measured" and the series starts accruing with the new format.
        val history =
            Gson().fromJson(
                """{"storyId":"rr_123","snapshots":[{"capturedAt":1000,"score":"4.5","totalChapters":10,
                   "publicationStatus":"ongoing","patreonPaidMembers":1137,"patreonMonthlyUsdCents":12102569}]}""",
                StoryMetricHistory::class.java,
            )
        assertNull(history.snapshots.single().patreonRaw)
        assertEquals(
            emptyList<Pair<Long, Double>>(),
            MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MONTHLY_USD),
        )
    }

    @Test
    fun rawPatreonSnapshotRoundTripsThroughGson() {
        val history =
            StoryMetricHistory(
                storyId = "rr_9",
                snapshots =
                    mutableListOf(
                        StoryMetricSnapshot(
                            capturedAt = 5L,
                            patreonRaw =
                                PatreonRawStats(
                                    capturedAt = 5L,
                                    paidMembers = 95,
                                    totalMembers = 106,
                                    exactMonthlyUsdCents = null,
                                    tiers = mutableListOf(PatreonRawTier(usdCents = 400, members = 12)),
                                ),
                        ),
                    ),
            )
        val restored = Gson().fromJson(Gson().toJson(history), StoryMetricHistory::class.java)
        assertEquals(history.snapshots, restored.snapshots)
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
