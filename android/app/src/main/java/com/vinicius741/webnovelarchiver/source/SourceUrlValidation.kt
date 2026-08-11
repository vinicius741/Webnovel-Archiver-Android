package com.vinicius741.webnovelarchiver.source

object SourceUrlValidation {
    fun isImportableStoryUrl(url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        return SourceRegistry.resolve(normalized, SourceUrlKind.STORY) != null
    }
}
