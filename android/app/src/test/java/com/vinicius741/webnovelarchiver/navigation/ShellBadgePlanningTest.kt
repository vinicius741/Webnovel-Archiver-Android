package com.vinicius741.webnovelarchiver.navigation

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellBadgePlanningTest {
    @Test
    fun `the downloads badge counts novels with waiting or running chapters`() {
        val queue =
            listOf(
                DownloadJob(id = "1", storyId = "a", status = "downloading"),
                DownloadJob(id = "2", storyId = "a", status = "pending"),
                DownloadJob(id = "3", storyId = "b", status = "pending"),
                DownloadJob(id = "4", storyId = "c", status = "paused"),
                DownloadJob(id = "5", storyId = "d", status = "completed"),
                DownloadJob(id = "6", storyId = "e", status = "failed"),
            )
        assertEquals(2, ShellBadgePlanning.downloadsBadge(queue))
    }

    @Test
    fun `an empty queue shows no badge`() {
        assertEquals(0, ShellBadgePlanning.downloadsBadge(emptyList()))
    }
}
