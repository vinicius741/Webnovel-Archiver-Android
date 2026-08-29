package com.vinicius741.webnovelarchiver.ai

import java.util.Locale

/**
 * Pure presentation rules for OpenRouter model catalog entries shown in the AI settings model
 * picker: pricing labels and search filtering. Kept free of Android types so it unit-tests like
 * the other planning objects.
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
