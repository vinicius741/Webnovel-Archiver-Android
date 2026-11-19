package com.vinicius741.webnovelarchiver.domain.model

/**
 * One stretch of a novel in which the main character keeps the same apparent life stage, found by
 * the cover scan (`CoverPhasePlanning`). Chapter numbers are 1-based positions in [Story.chapters];
 * [downloadedChapters] counts the downloaded chapters inside the range.
 */
data class StoryPhase(
    val stage: LifeStage,
    val firstChapter: Int,
    val lastChapter: Int,
    val downloadedChapters: Int,
) {
    val chapters: IntRange get() = firstChapter..lastChapter

    /** "Adult · Ch 76–300". */
    val label: String get() = "${stage.label} · Ch $firstChapter–$lastChapter"
}

/** Apparent life stage of a novel's main character, in age order. [key] is the persisted wire value. */
enum class LifeStage(
    val key: String,
    val label: String,
) {
    CHILD("child", "Child"),
    TEEN("teen", "Teen"),
    ADULT("adult", "Adult"),
    ELDER("elder", "Elder"),
    ;

    companion object {
        fun fromKey(key: String?): LifeStage? = entries.firstOrNull { it.key == key }
    }
}
