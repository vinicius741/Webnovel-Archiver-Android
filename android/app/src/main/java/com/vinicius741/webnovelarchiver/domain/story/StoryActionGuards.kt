package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story

object StoryActionGuards {
    const val ARCHIVED_ACTION_TITLE = "Archived Snapshot"

    fun archivedActionMessage(action: String): String = "$action is disabled for archived snapshots. Use the active story entry instead."

    /** Shared policy for actions that mutate or fetch the active story. */
    fun canModifyStory(story: Story): Boolean = story.isArchived != true

    /** Compatibility names for feature callers not yet migrated to the shared policy name. */
    fun canSync(story: Story): Boolean = canModifyStory(story)

    fun canQueueDownloads(story: Story): Boolean = canModifyStory(story)
}
