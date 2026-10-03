package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

/** A failed public catalog lookup must not prevent generation with a manually entered model. */
internal suspend fun OpenRouterClient.reasoningEffortFor(
    settings: AiSettings,
    modelId: String,
): String? {
    val models = modelsOrNull()
    return AiReasoningPlanning.effortFor(settings, modelId, models?.firstOrNull { it.id == modelId })
}

internal suspend fun OpenRouterClient.modelsOrNull(): List<OpenRouterModel>? =
    cachedModels ?: try {
        fetchModels()
    } catch (error: CancellationException) {
        throw error
    } catch (error: OpenRouterException) {
        Timber.w(error, "Model catalog unavailable; using saved reasoning preferences")
        null
    } catch (error: IOException) {
        Timber.w(error, "Could not fetch model catalog; using saved reasoning preferences")
        null
    }
