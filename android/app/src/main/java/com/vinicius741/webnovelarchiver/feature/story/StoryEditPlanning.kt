package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning

/**
 * A quick story edit a screen shows before storage confirms it. Each one sets a target value
 * rather than flipping one, so showing it on a story that already has it changes nothing.
 */
sealed interface StoryEdit {
    /** Moves the bookmark to [chapterId], or clears it when null. */
    data class Bookmark(
        val chapterId: String?,
    ) : StoryEdit

    data class ShowAiDescription(
        val show: Boolean,
    ) : StoryEdit

    data class ShowAiCover(
        val show: Boolean,
    ) : StoryEdit
}

/** The edits a screen shows ahead of storage, in tap order; each is dropped once its write ends. */
data class PendingStoryEdits(
    val edits: List<Pair<Long, StoryEdit>> = emptyList(),
    val nextId: Long = 1L,
) {
    fun isEmpty(): Boolean = edits.isEmpty()

    /** Adds [edit] under [nextId]. */
    fun plus(edit: StoryEdit): PendingStoryEdits = copy(edits = edits + (nextId to edit), nextId = nextId + 1)

    /** Drops the edit [id], written or failed: the story storage publishes is the truth again. */
    fun settle(id: Long): PendingStoryEdits = copy(edits = edits.filterNot { it.first == id })
}

/** Shows quick story edits on the published story before their storage transaction commits. */
object StoryEditPlanning {
    /** The story as the screen shows it: [base] with every pending edit applied in tap order; [base] itself when none changes it. */
    fun shown(
        base: Story?,
        pending: PendingStoryEdits,
    ): Story? = base?.let { pending.edits.fold(it) { story, (_, edit) -> apply(story, edit) } }

    /**
     * Applies one edit the way the repository's write would, or returns [story] unchanged when it
     * already shows it or the write would be rejected (a chapter sync removed, a missing AI text or cover).
     */
    fun apply(
        story: Story,
        edit: StoryEdit,
    ): Story =
        when (edit) {
            is StoryEdit.Bookmark -> withBookmark(story, edit.chapterId)
            is StoryEdit.ShowAiDescription ->
                if (story.aiDescription == null ||
                    story.showAiDescription == edit.show
                ) {
                    story
                } else {
                    story.copy(showAiDescription = edit.show)
                }
            is StoryEdit.ShowAiCover ->
                if (story.aiCoverPath.isNullOrBlank() || story.showAiCover == edit.show) story else story.copy(showAiCover = edit.show)
        }

    /** The bookmark edit for a tap on [chapterId]'s bookmark, as [shown] shows it: the bookmarked chapter clears it. */
    fun bookmarkTap(
        shown: Story,
        chapterId: String,
    ): StoryEdit.Bookmark = StoryEdit.Bookmark(if (shown.lastReadChapterId == chapterId) null else chapterId)

    private fun withBookmark(
        story: Story,
        chapterId: String?,
    ): Story {
        val current = story.lastReadChapterId
        return when {
            current == chapterId -> story
            chapterId == null -> StoryBookmarkPlanning.withBookmark(story, checkNotNull(current), toggleExisting = true)
            story.chapters.none { it.id == chapterId } -> story
            else -> StoryBookmarkPlanning.withBookmark(story, chapterId, toggleExisting = false)
        }
    }
}
