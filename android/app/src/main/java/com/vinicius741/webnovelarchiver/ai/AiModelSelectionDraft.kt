package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort

/** A model and its reasoning level are committed together when the picker is confirmed. */
data class AiModelSelection(
    val modelId: String,
    val reasoningEffort: String? = null,
)

/** Picker edits stay local until confirmation, including edits made while browsing other models. */
class AiModelSelectionDraft(
    private val settings: AiSettings,
    initialModelId: String,
) {
    var modelId = initialModelId
        private set
    private val efforts = settings.reasoningEfforts.toMutableMap()

    fun selectModel(id: String) {
        modelId = id
    }

    fun selectEffort(effort: String) {
        efforts[modelId] = effort
    }

    fun selection(models: List<OpenRouterModel>): AiModelSelection =
        AiModelSelection(
            modelId,
            AiReasoningPlanning.effortFor(settings.copy(reasoningEfforts = efforts), modelId, models.firstOrNull { it.id == modelId })
                ?: AiReasoningEffort.MODEL_DEFAULT,
        )
}
