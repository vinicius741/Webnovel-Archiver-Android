package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageUiTest {
    @Test
    fun `one step cover cost includes prompt retry and image call`() {
        val ledger =
            AiUsageLedger(
                recentRecords =
                    mutableListOf(
                        record("operation", AI_FEATURE_COVER_PROMPT, "0.001", "empty"),
                        record("operation", AI_FEATURE_COVER_PROMPT, "0.002", "completed"),
                        record("operation", AI_FEATURE_COVER_IMAGE, "0.06", "completed"),
                    ),
            )

        assertEquals(
            "OpenRouter: $0.06 · 3 calls",
            latestAiOperationCostLine(ledger, STORY_ID, AI_FEATURE_COVER_IMAGE),
        )
    }

    @Test
    fun `unknown cost stays visible instead of becoming zero`() {
        val ledger = AiUsageLedger(recentRecords = mutableListOf(record("operation", AI_FEATURE_DESCRIPTION, null, "completed")))

        assertEquals(
            "OpenRouter: Cost unavailable · 1 call · 1 cost not reported",
            latestAiOperationCostLine(ledger, STORY_ID, AI_FEATURE_DESCRIPTION),
        )
    }

    @Test
    fun `unrelated story has no cost line`() {
        val ledger = AiUsageLedger(recentRecords = mutableListOf(record("operation", AI_FEATURE_DESCRIPTION, "0.01", "completed")))

        assertNull(latestAiOperationCostLine(ledger, "other-story", AI_FEATURE_DESCRIPTION))
    }

    private fun record(
        operationId: String,
        feature: String,
        costUsd: String?,
        outcome: String,
    ) = AiUsageRecord(
        id = "$operationId-$feature-$outcome",
        operationId = operationId,
        storyId = STORY_ID,
        feature = feature,
        model = "test/model",
        costUsd = costUsd,
        outcome = outcome,
    )

    private companion object {
        const val STORY_ID = "story"
    }
}
