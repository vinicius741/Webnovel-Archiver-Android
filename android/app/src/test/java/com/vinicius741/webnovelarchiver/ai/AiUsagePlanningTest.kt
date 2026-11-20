package com.vinicius741.webnovelarchiver.ai

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AiUsagePlanningTest {
    private val utc = ZoneOffset.UTC
    private val dayOne = Instant.parse("2026-08-19T10:00:00Z").toEpochMilli()
    private val dayTwo = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()

    @Test
    fun `recordAttempt aggregates all time day and month while retaining the normalized row`() {
        var ledger = AiUsageLedger()
        ledger = ledger.record(cost = "0.0400", timestamp = dayOne)
        ledger = ledger.record(cost = "0.000001", timestamp = dayOne + 1_000L)
        ledger = ledger.record(cost = null, timestamp = dayTwo)

        assertEquals("0.040001", ledger.allTimeCostUsd)
        assertEquals(3L, ledger.allTimeCallCount)
        assertEquals(1L, ledger.unknownCallCount)
        assertEquals(
            AiUsageSummaryExpected("0.040001", 2L, 0L),
            AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.TODAY, dayOne, utc).asExpected(),
        )
        assertEquals(
            AiUsageSummaryExpected("0.040001", 3L, 1L),
            AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.CURRENT_MONTH, dayTwo, utc).asExpected(),
        )
        assertEquals(
            AiUsageSummaryExpected("0.040001", 3L, 1L),
            AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.ALL_TIME, dayTwo, utc).asExpected(),
        )
        assertEquals("0.04", ledger.recentRecords[0].costUsd)
        assertNull(ledger.recentRecords[2].costUsd)
    }

    @Test
    fun `missing malformed and negative costs count as unknown without changing totals`() {
        var ledger = AiUsageLedger()
        ledger = ledger.record(cost = "not-a-number", timestamp = dayOne)
        ledger = ledger.record(cost = "", timestamp = dayOne + 1L)
        ledger = ledger.record(cost = "-0.25", timestamp = dayOne + 2L)

        assertEquals("0", ledger.allTimeCostUsd)
        assertEquals(3L, ledger.allTimeCallCount)
        assertEquals(3L, ledger.unknownCallCount)
        assertTrue(ledger.recentRecords.all { it.costUsd == null })
    }

    @Test
    fun `recent rows are capped while all time totals continue growing`() {
        var ledger = AiUsageLedger()
        repeat(AiUsagePlanning.MAX_RECENT_RECORDS + 7) { index ->
            ledger = ledger.record(cost = "0.000001", timestamp = dayOne + index)
        }

        assertEquals(AiUsagePlanning.MAX_RECENT_RECORDS, ledger.recentRecords.size)
        assertEquals(AiUsagePlanning.MAX_RECENT_RECORDS + 7L, ledger.allTimeCallCount)
        assertEquals("0.000507", ledger.allTimeCostUsd)
        assertEquals("operation-8", ledger.recentRecords.first().operationId)
    }

    @Test
    fun `formatCostUsd keeps small charges readable`() {
        assertEquals("$0.00", AiUsagePlanning.formatCostUsd("0"))
        assertEquals("$0.04", AiUsagePlanning.formatCostUsd("0.0400"))
        assertEquals("$0.000001", AiUsagePlanning.formatCostUsd("0.000001"))
        assertEquals("$0.0000000005", AiUsagePlanning.formatCostUsd("5E-10"))
        assertEquals("Cost unavailable", AiUsagePlanning.formatCostUsd(null))
        assertEquals("Cost unavailable", AiUsagePlanning.formatCostUsd("bad"))
    }

    @Test
    fun `ledger round trips through Gson with decimal strings intact`() {
        var ledger = AiUsageLedger()
        ledger = ledger.record(cost = "0.0000000005", timestamp = dayOne)

        val json = Gson().toJson(ledger)
        val restored = Gson().fromJson(json, AiUsageLedger::class.java)

        assertEquals(ledger, restored)
        assertEquals("0.0000000005", restored.recentRecords.single().costUsd)
        assertEquals(4L, restored.recentRecords.single().reasoningTokens)
        assertEquals(2L, restored.recentRecords.single().cachedTokens)
    }

    @Test
    fun `groupRecentRecords collapses decision scan calls and keeps distinct features separate`() {
        val records =
            listOf(
                usageRecord(
                    id = "old",
                    operationId = "op-desc",
                    feature = "description",
                    model = "claude",
                    cost = "0.02",
                    timestamp = dayOne,
                ).copy(totalTokens = 500L),
                usageRecord(
                    id = "jev-1",
                    operationId = "op-scan",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.00015",
                    timestamp = dayOne + 1_000L,
                ).copy(promptTokens = 400L, completionTokens = 20L),
                usageRecord(
                    id = "jev-2",
                    operationId = "op-scan",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.00017",
                    timestamp = dayOne + 2_000L,
                ).copy(promptTokens = 500L, completionTokens = 25L),
                usageRecord(
                    id = "jev-3",
                    operationId = "op-scan",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.00018",
                    timestamp = dayOne + 3_000L,
                ).copy(promptTokens = 600L, completionTokens = 30L),
                usageRecord(
                    id = "prompt",
                    operationId = "op-cover",
                    feature = "cover_prompt",
                    model = "claude",
                    cost = "0.00",
                    timestamp = dayOne + 4_000L,
                ).copy(totalTokens = 38_912L),
                usageRecord(
                    id = "image",
                    operationId = "op-cover",
                    feature = "cover_image",
                    model = "grok",
                    cost = "0.06",
                    timestamp = dayOne + 5_000L,
                ).copy(totalTokens = 4_868L),
            )

        val groups = AiUsagePlanning.groupRecentRecords(records, limit = 8)

        assertEquals(listOf("cover_image", "cover_prompt", "cover_selection", "description"), groups.map { it.feature })
        val scan = groups[2]
        assertEquals(3, scan.requestCount)
        assertEquals("0.0005", scan.costUsd)
        assertEquals(0, scan.unknownCostCount)
        assertEquals(1_575L, scan.totalTokens)
        assertEquals(dayOne + 3_000L, scan.latestTimestamp)
        assertEquals(dayOne + 1_000L, scan.earliestTimestamp)
        assertEquals(listOf("typesafe/jev"), scan.models)
    }

    @Test
    fun `groupRecentRecords merges legacy adjacent cover top-up scans and preserves interleaved operations`() {
        val records =
            listOf(
                usageRecord(
                    id = "a1",
                    operationId = "op-scan-1",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp = dayOne,
                ),
                usageRecord(
                    id = "a2",
                    operationId = "op-scan-2",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0002",
                    timestamp =
                        dayOne + 5_000L,
                ),
                usageRecord(
                    id = "b1",
                    operationId = "op-portrait",
                    storyId = "story-b",
                    feature = "portrait_selection",
                    model = "typesafe/jev",
                    cost = "0.0003",
                    timestamp =
                        dayOne + 6_000L,
                ),
                usageRecord(
                    id = "a3",
                    operationId = "op-scan-2",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0004",
                    timestamp =
                        dayOne + 7_000L,
                ),
            )

        val groups = AiUsagePlanning.groupRecentRecords(records, limit = 8)

        // Newest group is op-scan-2 (at +7s), followed by op-portrait (+6s), followed by op-scan-1 (not adjacent because op-portrait sits between).
        assertEquals(3, groups.size)
        assertEquals(2, groups[0].requestCount)
        assertEquals("0.0006", groups[0].costUsd)
        assertEquals("portrait_selection", groups[1].feature)
        assertEquals(1, groups[2].requestCount)

        // When op-scan-1 and op-scan-2 are directly adjacent within 1 minute for the same story, they coalesce into one row.
        val adjacent =
            listOf(
                usageRecord(
                    id = "a1",
                    operationId = "op-scan-1",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp = dayOne,
                ),
                usageRecord(
                    id = "a2",
                    operationId = "op-scan-2",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0002",
                    timestamp =
                        dayOne + 5_000L,
                ),
            )
        val merged = AiUsagePlanning.groupRecentRecords(adjacent, limit = 8)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().requestCount)
        assertEquals("0.0003", merged.single().costUsd)
    }

    @Test
    fun `groupRecentRecords applies limit after grouping and tracks unknown costs`() {
        val records =
            buildList {
                add(
                    usageRecord(
                        id = "older",
                        operationId = "op-old",
                        feature = "description",
                        model = "claude",
                        cost = "0.01",
                        timestamp = dayOne,
                    ),
                )
                repeat(12) { index ->
                    add(
                        usageRecord(
                            id = "jev-$index",
                            operationId = "op-scan",
                            feature = "cover_selection",
                            model = "typesafe/jev",
                            cost = if (index == 0) null else "0.0001",
                            timestamp = dayOne + 1_000L + index,
                        ),
                    )
                }
                add(
                    usageRecord(
                        id = "blank-1",
                        operationId = "",
                        feature = "cover_selection",
                        model = "typesafe/jev",
                        cost = null,
                        timestamp =
                            dayOne + 2_000L,
                    ),
                )
                add(
                    usageRecord(
                        id = "blank-2",
                        operationId = "",
                        feature = "cover_selection",
                        model = "typesafe/jev",
                        cost = null,
                        timestamp =
                            dayOne + 2_001L,
                    ),
                )
            }

        val groups = AiUsagePlanning.groupRecentRecords(records, limit = 4)

        assertEquals(4, groups.size)
        assertNull(groups[0].costUsd)
        assertEquals(1, groups[0].unknownCostCount)
        assertEquals(1, groups[1].requestCount)
        val scan = groups[2]
        assertEquals(12, scan.requestCount)
        assertEquals("0.0011", scan.costUsd)
        assertEquals(1, scan.unknownCostCount)
        assertEquals("description", groups[3].feature)
    }

    @Test
    fun `groupRecentRecords does not merge separate stories, models, non-cover selections, or scans over a minute apart`() {
        val records =
            listOf(
                usageRecord(
                    id = "s1",
                    operationId = "op-1",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp = dayOne,
                ),
                usageRecord(
                    id = "s2",
                    operationId = "op-2",
                    storyId = "story-b",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 1_000L,
                ),
                usageRecord(
                    id = "s3",
                    operationId = "op-3",
                    storyId = "story-b",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 120_000L,
                ),
                usageRecord(
                    id = "s4",
                    operationId = "op-4",
                    storyId = "story-b",
                    feature = "cover_selection",
                    model = "other/model",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 121_000L,
                ),
                usageRecord(
                    id = "s5",
                    operationId = "op-5",
                    storyId = null,
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 122_000L,
                ),
                usageRecord(
                    id = "s6",
                    operationId = "op-6",
                    storyId = "",
                    feature = "cover_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 123_000L,
                ),
                usageRecord(
                    id = "p1",
                    operationId = "op-p1",
                    storyId = "story-a",
                    feature = "portrait_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 124_000L,
                ),
                usageRecord(
                    id = "p2",
                    operationId = "op-p2",
                    storyId = "story-a",
                    feature = "portrait_selection",
                    model = "typesafe/jev",
                    cost = "0.0001",
                    timestamp =
                        dayOne + 125_000L,
                ),
            )

        val groups = AiUsagePlanning.groupRecentRecords(records, limit = 10)

        assertEquals(8, groups.size)
        assertEquals(emptyList<RecentAiRequestGroup>(), AiUsagePlanning.groupRecentRecords(records, limit = 0))
        assertEquals(emptyList<RecentAiRequestGroup>(), AiUsagePlanning.groupRecentRecords(records, limit = -1))
    }

    @Test
    fun `recordTokens prefers totalTokens and groupRecentRecords tracks unknown tokens`() {
        val withTotal =
            usageRecord(id = "t1", operationId = "op-t", feature = "cover_selection", model = "jev", cost = "0.0001", timestamp = dayOne)
                .copy(totalTokens = 900L, promptTokens = 400L, completionTokens = 20L)
        val promptOnly =
            usageRecord(
                id = "t2",
                operationId = "op-t",
                feature = "cover_selection",
                model = "jev",
                cost = "0.0001",
                timestamp =
                    dayOne + 1_000L,
            ).copy(promptTokens = 400L)
        val bothSides =
            usageRecord(
                id = "t3",
                operationId = "op-t",
                feature = "cover_selection",
                model = "jev",
                cost = "0.0001",
                timestamp =
                    dayOne + 2_000L,
            ).copy(promptTokens = 300L, completionTokens = 50L)

        assertEquals(900L, AiUsagePlanning.recordTokens(withTotal))
        assertNull(AiUsagePlanning.recordTokens(promptOnly))
        assertEquals(350L, AiUsagePlanning.recordTokens(bothSides))

        val group = AiUsagePlanning.groupRecentRecords(listOf(withTotal, promptOnly, bothSides), limit = 8).single()
        assertEquals(1_250L, group.totalTokens)
        assertEquals(1, group.unknownTokenCount)
    }

    @Test
    fun `groupRecentRecords merges same operation interleaved with another operation at newest position`() {
        val records =
            listOf(
                usageRecord(
                    id = "a1",
                    operationId = "op-a",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "jev",
                    cost = "0.0001",
                    timestamp = dayOne,
                ),
                usageRecord(
                    id = "b1",
                    operationId = "op-b",
                    storyId = "story-b",
                    feature = "description",
                    model = "claude",
                    cost = "0.01",
                    timestamp =
                        dayOne + 1_000L,
                ),
                usageRecord(
                    id = "a2",
                    operationId = "op-a",
                    storyId = "story-a",
                    feature = "cover_selection",
                    model = "jev",
                    cost = "0.0002",
                    timestamp =
                        dayOne + 2_000L,
                ),
            )

        val groups = AiUsagePlanning.groupRecentRecords(records, limit = 8)

        assertEquals(listOf("cover_selection", "description"), groups.map { it.feature })
        assertEquals(2, groups[0].requestCount)
        assertEquals("0.0003", groups[0].costUsd)
        assertEquals(dayOne + 2_000L, groups[0].latestTimestamp)
    }

    private fun usageRecord(
        id: String,
        operationId: String,
        storyId: String? = "story",
        feature: String,
        model: String,
        cost: String?,
        timestamp: Long,
    ) = AiUsageRecord(
        id = id,
        operationId = operationId,
        timestamp = timestamp,
        storyId = storyId,
        feature = feature,
        model = model,
        costUsd = cost,
        outcome = "completed",
    )

    private fun AiUsageLedger.record(
        cost: String?,
        timestamp: Long,
    ): AiUsageLedger =
        AiUsagePlanning.recordAttempt(
            this,
            AiUsageRecord(
                id = "id-${allTimeCallCount + 1L}",
                operationId = "operation-${allTimeCallCount + 1L}",
                timestamp = timestamp,
                storyId = "story",
                feature = "description",
                model = "model",
                reasoningTokens = 4L,
                cachedTokens = 2L,
                costUsd = cost,
                outcome = "success",
            ),
            utc,
        )

    private data class AiUsageSummaryExpected(
        val costUsd: String,
        val callCount: Long,
        val unknownCallCount: Long,
    )

    private fun com.vinicius741.webnovelarchiver.domain.model.AiUsagePeriodSummary.asExpected() =
        AiUsageSummaryExpected(costUsd, callCount, unknownCallCount)
}
