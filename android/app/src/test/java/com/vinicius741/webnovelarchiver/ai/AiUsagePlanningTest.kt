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
