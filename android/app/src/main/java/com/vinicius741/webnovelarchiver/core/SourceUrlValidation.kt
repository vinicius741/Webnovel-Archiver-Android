package com.vinicius741.webnovelarchiver.core

object SourceUrlValidation {
    fun isImportableStoryUrl(url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        return isRoyalRoadStoryUrl(normalized) || isScribbleHubStoryUrl(normalized)
    }

    private fun isRoyalRoadStoryUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Regex("""https?://(?:www\.)?royalroad\.com/fiction/\d+""").containsMatchIn(lower) &&
            !lower.contains("/chapter/")
    }

    private fun isScribbleHubStoryUrl(url: String): Boolean =
        Regex("""https?://(?:www\.)?scribblehub\.com/series/\d+""").containsMatchIn(url)
}
