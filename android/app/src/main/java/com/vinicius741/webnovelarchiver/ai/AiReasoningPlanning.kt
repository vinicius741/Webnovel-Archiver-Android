package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort

/** Catalog-driven effort selection shared by the picker and generation requests. */
object AiReasoningPlanning {
    fun allowedEfforts(model: OpenRouterModel?): List<String> {
        // Manual ids and an unavailable catalog still allow an explicit user choice.
        if (model == null) return AiReasoningEffort.levels
        val options = model.reasoning ?: return emptyList()
        val supported = options.supportedEfforts ?: AiReasoningEffort.levels
        return AiReasoningEffort.levels.filter { it in supported && (it != "none" || !options.mandatory) }
    }

    fun effortFor(
        settings: AiSettings,
        modelId: String,
        model: OpenRouterModel?,
    ): String? {
        val selected = settings.reasoningEfforts[modelId] ?: AiReasoningEffort.LEGACY_DEFAULT
        return selected.takeIf { it in allowedEfforts(model) }
    }
}
