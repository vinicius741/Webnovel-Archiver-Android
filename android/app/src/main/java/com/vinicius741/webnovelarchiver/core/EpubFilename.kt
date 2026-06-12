package com.vinicius741.webnovelarchiver.core

object EpubFilename {
    private const val MAX_BASE_LENGTH = 80

    fun forRange(title: String, startChapter: Int, endChapter: Int): String {
        val base = sanitizeBase(title)
        return "${base}_Ch$startChapter-$endChapter.epub"
    }

    fun sanitizeBase(title: String): String {
        return title
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .lowercase()
            .trim('_')
            .ifBlank { "story" }
            .take(MAX_BASE_LENGTH)
            .trim('_')
            .ifBlank { "story" }
    }
}
