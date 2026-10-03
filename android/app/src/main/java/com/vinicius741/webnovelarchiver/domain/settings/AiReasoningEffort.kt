package com.vinicius741.webnovelarchiver.domain.settings

/** OpenRouter's unified reasoning levels, in increasing order. */
object AiReasoningEffort {
    const val MODEL_DEFAULT = "default"
    const val LEGACY_DEFAULT = "low"
    val levels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    fun label(effort: String?): String =
        when (effort) {
            "none" -> "Off"
            "minimal" -> "Minimal"
            "low" -> "Low"
            "medium" -> "Medium"
            "high" -> "High"
            "xhigh" -> "Extra high"
            "max" -> "Maximum"
            else -> "Model default"
        }

    /** Compact form for the picker's segmented control, where all levels must fit side by side. */
    fun shortLabel(effort: String?): String =
        when (effort) {
            "none" -> "Off"
            "minimal" -> "Min"
            "low" -> "Low"
            "medium" -> "Med"
            "high" -> "High"
            "xhigh" -> "XHigh"
            "max" -> "Max"
            else -> "Default"
        }

    fun normalize(saved: Map<String, String?>?): Map<String, String> =
        saved
            .orEmpty()
            .entries
            .mapNotNull { (model, effort) ->
                val id = model.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val value = effort?.trim()?.takeIf { it in levels || it == MODEL_DEFAULT } ?: return@mapNotNull null
                id to value
            }.toMap()
}
