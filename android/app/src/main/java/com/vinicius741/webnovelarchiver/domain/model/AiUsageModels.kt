package com.vinicius741.webnovelarchiver.domain.model

/**
 * One locally recorded attempt to call an AI provider. Cost is decimal text so aggregating
 * sub-cent charges never routes through binary floating point. Null means no cost was reported.
 */
data class AiUsageRecord(
    val id: String = "",
    val operationId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val storyId: String? = null,
    val feature: String = "",
    val model: String = "",
    val generationId: String? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedTokens: Long? = null,
    val costUsd: String? = null,
    val outcome: String = "unknown",
)

/** Aggregates shown for a time window in the local AI spend summary. */
data class AiUsagePeriodSummary(
    val costUsd: String = "0",
    val callCount: Long = 0L,
    val unknownCallCount: Long = 0L,
)

/** The three spend windows displayed together in AI Settings. */
data class AiUsageSummary(
    val todayCostUsd: String = "0",
    val monthCostUsd: String = "0",
    val allTimeCostUsd: String = "0",
    val todayCallCount: Long = 0L,
    val monthCallCount: Long = 0L,
    val allTimeCallCount: Long = 0L,
    val unknownCallCount: Long = 0L,
)

/** Device-local AI spend ledger. The provider key and full prompts never belong in this document. */
data class AiUsageLedger(
    val allTimeCostUsd: String = "0",
    val allTimeCallCount: Long = 0L,
    val unknownCallCount: Long = 0L,
    val dailySummaries: MutableMap<String, AiUsagePeriodSummary> = mutableMapOf(),
    val monthlySummaries: MutableMap<String, AiUsagePeriodSummary> = mutableMapOf(),
    val recentRecords: MutableList<AiUsageRecord> = mutableListOf(),
)
