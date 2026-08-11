package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDownloadPlanningTest {
    @Test
    fun newStoryDoesNotAutoDownloadItsInitialCatalog() {
        val synced = story(listOf("c1", "c2"), pending = listOf("c1", "c2"))

        val plan = SyncDownloadPlanning.plan(before = null, synced = synced)

        assertEquals(SyncDownloadAction.NONE, plan.action)
        assertEquals(emptyList<Int>(), plan.chapterIndexes)
    }

    @Test
    fun oldPendingBacklogIsNotRestartedByAnotherSync() {
        val before = story(listOf("old"), pending = listOf("old"))
        val synced = story(listOf("old"), pending = listOf("old"))

        val plan = SyncDownloadPlanning.plan(before, synced)

        assertEquals(SyncDownloadAction.NONE, plan.action)
        assertEquals(emptyList<String>(), plan.chapterIds)
    }

    @Test
    fun onlyChaptersDiscoveredByCurrentSyncAreSelected() {
        val before = story(listOf("downloaded", "old"), pending = listOf("old"))
        val synced =
            story(
                ids = listOf("downloaded", "old", "new-1", "new-2"),
                pending = listOf("old", "new-2", "new-1", "missing"),
                downloaded = setOf("downloaded"),
            )

        val plan = SyncDownloadPlanning.plan(before, synced)

        assertEquals(SyncDownloadAction.AUTO_QUEUE, plan.action)
        assertEquals(listOf(2, 3), plan.chapterIndexes)
        assertEquals(listOf("new-1", "new-2"), plan.chapterIds)
    }

    @Test
    fun downloadedCurrentSyncChapterIsNotQueued() {
        val before = story(emptyList(), pending = emptyList())
        val synced = story(listOf("new"), pending = listOf("new"), downloaded = setOf("new"))

        val plan = SyncDownloadPlanning.plan(before, synced)

        assertEquals(SyncDownloadAction.NONE, plan.action)
    }

    @Test
    fun updateAtLimitQueuesAutomatically() {
        val before = story(emptyList(), pending = emptyList())
        val synced = story((1..20).map { "c$it" }, pending = (1..20).map { "c$it" })

        val plan = SyncDownloadPlanning.plan(before, synced)

        assertEquals(SyncDownloadAction.AUTO_QUEUE, plan.action)
        assertEquals(20, plan.chapterIndexes.size)
    }

    @Test
    fun updateAboveLimitRequiresReview() {
        val before = story(emptyList(), pending = emptyList())
        val synced = story((1..21).map { "c$it" }, pending = (1..21).map { "c$it" })

        val plan = SyncDownloadPlanning.plan(before, synced)

        assertEquals(SyncDownloadAction.REVIEW, plan.action)
        assertEquals(21, plan.chapterIndexes.size)
    }

    private fun story(
        ids: List<String>,
        pending: List<String>?,
        downloaded: Set<String> = emptySet(),
    ): Story =
        Story(
            id = "story",
            chapters = ids.map { id -> Chapter(id = id, downloaded = id in downloaded) }.toMutableList(),
            pendingNewChapterIds = pending?.toMutableList(),
        )
}
