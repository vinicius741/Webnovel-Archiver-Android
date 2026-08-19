package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import java.math.BigDecimal

internal const val AI_FEATURE_DESCRIPTION = "description"
internal const val AI_FEATURE_COVER_PROMPT = "cover_prompt"
internal const val AI_FEATURE_COVER_IMAGE = "cover_image"

/** Cost line for the newest operation that reached [terminalFeature] for this story. */
internal fun latestAiOperationCostLine(
    ledger: AiUsageLedger,
    storyId: String,
    terminalFeature: String,
): String? {
    val terminalRecord =
        ledger.recentRecords
            .asReversed()
            .firstOrNull { it.storyId == storyId && it.feature == terminalFeature }
            ?: return null
    val operationRecords =
        if (terminalRecord.operationId.isBlank()) {
            listOf(terminalRecord)
        } else {
            ledger.recentRecords.filter { it.operationId == terminalRecord.operationId }
        }
    val knownCosts = operationRecords.mapNotNull { AiUsagePlanning.normalizeCost(it.costUsd)?.let(::BigDecimal) }
    val unknownCount = operationRecords.size - knownCosts.size
    val costLabel =
        if (knownCosts.isEmpty()) {
            "Cost unavailable"
        } else {
            AiUsagePlanning.formatCostUsd(knownCosts.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString())
        }
    val callLabel = if (operationRecords.size == 1) "1 call" else "${operationRecords.size} calls"
    val unknownLabel =
        when (unknownCount) {
            0 -> ""
            1 -> " · 1 cost not reported"
            else -> " · $unknownCount costs not reported"
        }
    return "OpenRouter: $costLabel · $callLabel$unknownLabel"
}
