package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story

object StoryActionGuards {
    const val ARCHIVED_ACTION_TITLE = "Archived Snapshot"

    fun archivedActionMessage(action: String): String = "$action is disabled for archived snapshots. Use the active story entry instead."

    fun canSync(story: Story): Boolean = story.isArchived != true

    fun canQueueDownloads(story: Story): Boolean = story.isArchived != true
}
