package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsagePeriodSummary
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.AiUsageSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Time windows supported by the local AI spend summary. */
enum class AiUsagePeriod {
    TODAY,
    CURRENT_MONTH,
    ALL_TIME,
}

/** Pure ledger rules. Network clients and storage should hand attempts to this object. */
@Suppress("TooManyFunctions") // One cohesive set of exact ledger transforms and display helpers.
object AiUsagePlanning {
    const val MAX_RECENT_RECORDS = 500

    private val ZERO = BigDecimal.ZERO

    /** Adds one API attempt, preserving unknown costs instead of silently treating them as zero. */
    fun recordAttempt(
        ledger: AiUsageLedger,
        record: AiUsageRecord,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiUsageLedger {
        val normalizedLedger = normalizeLedger(ledger)
        val normalizedRecord = record.copy(costUsd = normalizeCost(record.costUsd))
        val cost = normalizedRecord.costUsd
        val dayKey = dayKey(normalizedRecord.timestamp, zone)
        val monthKey = monthKey(normalizedRecord.timestamp, zone)
        val day = normalizedLedger.dailySummaries[dayKey] ?: AiUsagePeriodSummary()
        val month = normalizedLedger.monthlySummaries[monthKey] ?: AiUsagePeriodSummary()
        val hasKnownCost = cost != null
        val nextDay = day.add(cost, hasKnownCost)
        val nextMonth = month.add(cost, hasKnownCost)
        val nextRecent =
            (normalizedLedger.recentRecords + normalizedRecord)
                .takeLast(MAX_RECENT_RECORDS)
                .toMutableList()

        return normalizedLedger.copy(
            allTimeCostUsd = addCost(normalizedLedger.allTimeCostUsd, cost),
            allTimeCallCount = normalizedLedger.allTimeCallCount + 1L,
            unknownCallCount = normalizedLedger.unknownCallCount + if (hasKnownCost) 0L else 1L,
            dailySummaries = normalizedLedger.dailySummaries.toMutableMap().apply { put(dayKey, nextDay) },
            monthlySummaries = normalizedLedger.monthlySummaries.toMutableMap().apply { put(monthKey, nextMonth) },
            recentRecords = nextRecent,
        )
    }

    /** Returns a sanitized copy so malformed or pre-ledger JSON cannot crash the UI. */
    fun normalizeLedger(ledger: AiUsageLedger): AiUsageLedger {
        val daily =
            ledger.dailySummaries
                .mapNotNull { (key, summary) ->
                    val normalized = normalizeSummary(summary)
                    key.takeIf { it.isNotBlank() }?.let { it to normalized }
                }.toMap()
                .toMutableMap()
        val monthly =
            ledger.monthlySummaries
                .mapNotNull { (key, summary) ->
                    val normalized = normalizeSummary(summary)
                    key.takeIf { it.isNotBlank() }?.let { it to normalized }
                }.toMap()
                .toMutableMap()
        val recent =
            ledger.recentRecords
                .map { it.copy(costUsd = normalizeCost(it.costUsd)) }
                .takeLast(MAX_RECENT_RECORDS)
                .toMutableList()
        return ledger.copy(
            allTimeCostUsd = normalizeCost(ledger.allTimeCostUsd) ?: "0",
            allTimeCallCount = ledger.allTimeCallCount.coerceAtLeast(0L),
            unknownCallCount = ledger.unknownCallCount.coerceAtLeast(0L),
            dailySummaries = daily,
            monthlySummaries = monthly,
            recentRecords = recent,
        )
    }

    fun summaryForPeriod(
        ledger: AiUsageLedger,
        period: AiUsagePeriod,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiUsagePeriodSummary {
        val normalized = normalizeLedger(ledger)
        return when (period) {
            AiUsagePeriod.ALL_TIME ->
                AiUsagePeriodSummary(
                    costUsd = normalized.allTimeCostUsd,
                    callCount = normalized.allTimeCallCount,
                    unknownCallCount = normalized.unknownCallCount,
                )
            AiUsagePeriod.TODAY ->
                normalized.dailySummaries[dayKey(now, zone)] ?: AiUsagePeriodSummary()
            AiUsagePeriod.CURRENT_MONTH ->
                normalized.monthlySummaries[monthKey(now, zone)] ?: AiUsagePeriodSummary()
        }
    }

    /** Compatibility overload for callers that request one period at a time. */
    fun summary(
        ledger: AiUsageLedger,
        period: AiUsagePeriod,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiUsagePeriodSummary = summaryForPeriod(ledger, period, now, zone)

    /** Returns today, current month, and all-time figures in one snapshot for the Settings UI. */
    fun summary(
        ledger: AiUsageLedger,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiUsageSummary {
        val today = summaryForPeriod(ledger, AiUsagePeriod.TODAY, now, zone)
        val month = summaryForPeriod(ledger, AiUsagePeriod.CURRENT_MONTH, now, zone)
        val allTime = summaryForPeriod(ledger, AiUsagePeriod.ALL_TIME, now, zone)
        return AiUsageSummary(
            todayCostUsd = today.costUsd,
            monthCostUsd = month.costUsd,
            allTimeCostUsd = allTime.costUsd,
            todayCallCount = today.callCount,
            monthCallCount = month.callCount,
            allTimeCallCount = allTime.callCount,
            unknownCallCount = allTime.unknownCallCount,
        )
    }

    /** Formats small charges without scientific notation or binary rounding artifacts. */
    fun formatCostUsd(costUsd: String?): String {
        val value = costUsd?.let(::parseCost) ?: return "Cost unavailable"
        if (value.compareTo(ZERO) == 0) return "$0.00"
        val display =
            if (value.abs() >= BigDecimal("0.01")) {
                value.setScale(2, RoundingMode.HALF_UP).toPlainString()
            } else {
                value.stripTrailingZeros().toPlainString()
            }
        return "$$display"
    }

    /** Compatibility-friendly alias for callers formatting a known decimal string. */
    fun formatUsd(costUsd: String): String = formatCostUsd(costUsd)

    /** Null for blank, malformed, negative, or non-finite provider values. */
    fun normalizeCost(costUsd: String?): String? {
        val value = costUsd?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseCost) ?: return null
        return value.stripTrailingZeros().toPlainString()
    }

    private fun parseCost(value: String): BigDecimal? =
        runCatching { BigDecimal(value) }
            .getOrNull()
            ?.takeIf { it.signum() >= 0 }

    private fun addCost(
        left: String?,
        right: String?,
    ): String {
        val leftValue = left?.let(::parseCost) ?: ZERO
        val rightValue = right?.let(::parseCost) ?: ZERO
        return leftValue.add(rightValue).stripTrailingZeros().toPlainString()
    }

    private fun normalizeSummary(summary: AiUsagePeriodSummary): AiUsagePeriodSummary =
        summary.copy(
            costUsd = normalizeCost(summary.costUsd) ?: "0",
            callCount = summary.callCount.coerceAtLeast(0L),
            unknownCallCount = summary.unknownCallCount.coerceAtLeast(0L),
        )

    private fun AiUsagePeriodSummary.add(
        costUsd: String?,
        knownCost: Boolean,
    ): AiUsagePeriodSummary =
        copy(
            costUsd = addCost(this.costUsd, costUsd),
            callCount = callCount + 1L,
            unknownCallCount = unknownCallCount + if (knownCost) 0L else 1L,
        )

    private fun dayKey(
        timestamp: Long,
        zone: ZoneId,
    ): String =
        Instant
            .ofEpochMilli(timestamp)
            .atZone(zone)
            .toLocalDate()
            .toString()

    private fun monthKey(
        timestamp: Long,
        zone: ZoneId,
    ): String = YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zone)).toString()
}
