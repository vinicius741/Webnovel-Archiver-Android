package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException
import java.util.Locale

/**
 * Catalog-driven presentation and selection rules for OpenRouter models: pricing labels, search
 * filtering, "known good" rewrite marks, and reasoning-effort selection. The OpenRouterClient
 * extensions tolerate a missing catalog so generation with a manually entered model still works.
 * Kept free of Android types so it unit-tests like the other planning objects.
 */
object AiModelPresentation {
    /** e.g. "Free" or "$0.15 in · $0.60 out per 1M tokens". */
    fun priceLabel(model: OpenRouterModel): String {
        val prompt = model.promptPricePerToken?.toDoubleOrNull()
        val completion = model.completionPricePerToken?.toDoubleOrNull()
        if (prompt == null && completion == null) return "pricing unavailable"
        if ((prompt ?: 0.0) == 0.0 && (completion ?: 0.0) == 0.0) return "Free"
        val parts = mutableListOf<String>()
        prompt?.let { parts += "$${formatPricePerMillion(it)} in" }
        completion?.let { parts += "$${formatPricePerMillion(it)} out" }
        return parts.joinToString(" · ") + " per 1M tokens"
    }

    /** Case-insensitive id/name search with an optional free-only filter. */
    fun filter(
        models: List<OpenRouterModel>,
        query: String,
        freeOnly: Boolean,
    ): List<OpenRouterModel> {
        val needle = query.trim().lowercase(Locale.US)
        return models.filter { model ->
            (
                needle.isEmpty() || model.id.lowercase(Locale.US).contains(needle) ||
                    model.name.lowercase(Locale.US).contains(needle)
            ) &&
                (!freeOnly || model.isFree)
        }
    }

    /**
     * Rewrite models validated in the drift spike, matched by id substring so publisher prefixes
     * (openai/, deepseek/, …) do not have to be pinned exactly. Surfaced as a "Known good" filter
     * in the rewrite-model picker instead of prose on the screen.
     */
    private val KNOWN_GOOD_REWRITE_MODEL_FRAGMENTS =
        listOf(
            "gpt-5.6-terra",
            "gpt-5.6-sol",
            "grok-4.6",
            "glm-5.3",
            "deepseek-v4-pro-0813",
            "kimi-k2-0905",
        )

    fun isKnownGoodRewriteModel(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.US)
        return KNOWN_GOOD_REWRITE_MODEL_FRAGMENTS.any(id::contains)
    }

    private fun formatPricePerMillion(pricePerToken: Double): String {
        val perMillion = pricePerToken * 1_000_000
        val formatted =
            if (perMillion >= 1.0) {
                String.format(Locale.US, "%.2f", perMillion)
            } else {
                String.format(Locale.US, "%.4f", perMillion)
            }
        return formatted.trimEnd('0').trimEnd('.')
    }
}

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
