package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryActionGuardsTest {
    @Test
    fun activeStoriesCanSyncAndQueueDownloads() {
        val story = Story(id = "active", isArchived = false)

        assertTrue(StoryActionGuards.canSync(story))
        assertTrue(StoryActionGuards.canQueueDownloads(story))
    }

    @Test
    fun archivedStoriesCannotSyncOrQueueDownloads() {
        val story = Story(id = "archive", isArchived = true)

        assertFalse(StoryActionGuards.canSync(story))
        assertFalse(StoryActionGuards.canQueueDownloads(story))
    }

    @Test
    fun archivedActionMessageMatchesExpectedWording() {
        assertEquals(
            "Downloading is disabled for archived snapshots. Use the active story entry instead.",
            StoryActionGuards.archivedActionMessage("Downloading"),
        )
    }
}
