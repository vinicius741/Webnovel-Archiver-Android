package com.vinicius741.webnovelarchiver.epub

object EpubFilename {
    private const val MAX_BASE_LENGTH = 80

    fun forRange(
        title: String,
        startChapter: Int,
        endChapter: Int,
        generationId: String? = null,
    ): String {
        val base = sanitizeBase(title)
        val version = generationId?.let { "_g$it" }.orEmpty()
        return "${base}_Ch$startChapter-$endChapter$version.epub"
    }

    fun sanitizeBase(title: String): String =
        title
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .lowercase()
            .trim('_')
            .ifBlank { "story" }
            .take(MAX_BASE_LENGTH)
            .trim('_')
            .ifBlank { "story" }
}
