package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Coerces runtime-null values on a [Story] after Gson deserialization.
 *
 * Kotlin marks many Story fields non-null with defaults, but Gson can still leave them null when:
 *  - the on-disk JSON predates the field (e.g. [Story.publicationStatus]), and
 *  - the no-arg constructor that applies Kotlin defaults was stripped (R8 minify) so Gson allocates
 *    via Unsafe instead of running field initializers.
 *
 * The next [Story.copy] then crashes with "Parameter specified as non-null is null". Call this on
 * every storage read path so release builds survive legacy libraries.
 */
object StoryNormalization {
    data class CoercionResult(
        val story: Story,
        /** True when at least one runtime-null field was filled with a default. */
        val changed: Boolean,
    )

    @Suppress("SENSELESS_COMPARISON") // Gson/Java can null Kotlin non-null fields at runtime.
    fun coerceDefaults(story: Story): CoercionResult {
        var changed = false
        if (story.id == null) {
            story.id = ""
            changed = true
        }
        if (story.title == null) {
            story.title = ""
            changed = true
        }
        if (story.author == null) {
            story.author = ""
            changed = true
        }
        if (story.sourceUrl == null) {
            story.sourceUrl = ""
            changed = true
        }
        if (story.status == null) {
            story.status = DownloadStatus.idle
            changed = true
        }
        if (story.publicationStatus == null) {
            story.publicationStatus = PublicationStatus.unknown
            changed = true
        }
        if (story.chapters == null) {
            story.chapters = mutableListOf()
            changed = true
        } else {
            story.chapters.forEach { chapter ->
                if (chapter.id == null) {
                    chapter.id = ""
                    changed = true
                }
                if (chapter.title == null) {
                    chapter.title = ""
                    changed = true
                }
                if (chapter.url == null) {
                    chapter.url = ""
                    changed = true
                }
            }
        }
        return CoercionResult(story, changed)
    }
}
