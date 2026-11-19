package com.vinicius741.webnovelarchiver.domain.model

import java.io.File

/**
 * One character of a novel, from the Characters tab's "Find characters" run: the AI groups names
 * the app counted in the downloaded chapters into people. [id] is derived from [name], so finding
 * characters again keeps each character's portraits. Chapter numbers are 1-based positions in
 * [Story.chapters]; [mentions] counts every name and alias in the downloaded chapters.
 */
data class StoryCharacter(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val role: CharacterRole,
    val bio: String,
    val mentions: Int,
    val firstChapter: Int?,
    val lastChapter: Int?,
    /** Chapters a portrait scan reads first: the character's entrance, then where they are named most. */
    val keyChapters: List<Int> = emptyList(),
) {
    /** The name and every alias, longest first, so "Isabel Thorne" matches before "Isabel". */
    val names: List<String> get() = (listOf(name) + aliases).filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }

    /** "Ch 3–210", or null before the first count. */
    val span: String?
        get() =
            when {
                firstChapter == null || lastChapter == null -> null
                firstChapter == lastChapter -> "Ch $firstChapter"
                else -> "Ch $firstChapter–$lastChapter"
            }
}

/** How central a character is; [key] is the persisted wire value and the roster model's answer. */
enum class CharacterRole(
    val key: String,
    val label: String,
) {
    PROTAGONIST("protagonist", "Lead"),
    MAJOR("major", "Main character"),
    SUPPORTING("supporting", "Supporting"),
    ;

    companion object {
        fun fromKey(key: String?): CharacterRole = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: SUPPORTING
    }
}

/** One generated portrait of a character; [id] is the image's content hash. */
data class CharacterPortrait(
    val id: String,
    val prompt: String,
    val mediaType: String?,
    val image: File,
    val createdAt: Long,
)
