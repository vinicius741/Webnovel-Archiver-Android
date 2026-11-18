package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition

/**
 * The Overview's contextual primary action: keep reading from the bookmark, or, once every
 * downloaded chapter after it is read, download the rest first.
 */
object DetailsActionPlanning {
    /** A chapter the reader can open: [number] is its 1-based position; [resume] is true when it continues from the bookmark. */
    data class ReadTarget(
        val chapterId: String,
        val number: Int,
        val resume: Boolean,
    )

    data class Plan(
        /** Where Continue / Start reading opens; null when no downloaded chapter can be read from here. */
        val read: ReadTarget?,
        /** Chapters not downloaded yet; 0 when downloads are not possible for this story. */
        val remaining: Int,
        /** The story is caught up, so downloading the rest leads and reading becomes the second action. */
        val downloadFirst: Boolean,
        /** Every chapter is still to download (labels it "Download all"). */
        val nothingDownloaded: Boolean,
    )

    fun plan(
        story: Story,
        canDownload: Boolean,
    ): Plan {
        val chapters = story.chapters
        val bookmark = story.lastReadChapterId?.let { id -> chapters.indexOfFirst { it.id == id } } ?: -1
        val readIndex =
            if (bookmark >= 0) {
                (bookmark until chapters.size).firstOrNull { chapters[it].downloaded }
            } else {
                chapters.indexOfFirst { it.downloaded }.takeIf { it >= 0 }
            }
        val read = readIndex?.let { ReadTarget(chapters[it].id, it + 1, resume = bookmark >= 0) }
        val remaining = if (canDownload) chapters.count { !it.downloaded } else 0
        // Caught up: nothing downloaded is left after the bookmark (or there is no bookmark yet).
        val caughtUp = bookmark < 0 || ((bookmark + 1) until chapters.size).none { chapters[it].downloaded }
        return Plan(
            read = read,
            remaining = remaining,
            downloadFirst = remaining > 0 && (caughtUp || read == null),
            nothingDownloaded = remaining > 0 && remaining == chapters.size,
        )
    }

    /** "Download all 120" or "Download 21 remaining". */
    fun downloadLabel(plan: Plan): String =
        if (plan.nothingDownloaded) "Download all ${plan.remaining}" else "Download ${plan.remaining} remaining"

    /** "Continue reading · Ch. 3", or "Start reading · Ch. 1" before there is a bookmark. */
    fun readLabel(
        target: ReadTarget,
        short: Boolean = false,
    ): String {
        val verb = if (target.resume) "Continue" else "Start"
        return if (short) "$verb · Ch. ${target.number}" else "$verb reading · Ch. ${target.number}"
    }

    /** The story's own listening place, kept apart from the bookmark: [number] is its 1-based chapter. */
    data class ListenPlace(
        val number: Int,
        /** Playback reached the end of this chapter, the last one there was. */
        val finished: Boolean,
        val updatedAt: Long,
    )

    /** Where listening stopped, when it stopped in a chapter the story still has. */
    fun listenPlace(
        story: Story,
        position: TtsStoryPosition?,
    ): ListenPlace? {
        if (position == null || position.storyId != story.id) return null
        val index = story.chapters.indexOfFirst { it.id == position.chapterId }
        if (index < 0) return null
        return ListenPlace(index + 1, position.caughtUp, position.updatedAt)
    }

    /** "Listening at Ch. 142 · 2 days ago", or "Finished Ch. 200 · 2 days ago" once caught up. */
    fun listenPlaceLabel(
        place: ListenPlace,
        ago: CharSequence,
    ): String = if (place.finished) "Finished Ch. ${place.number} · $ago" else "Listening at Ch. ${place.number} · $ago"
}
