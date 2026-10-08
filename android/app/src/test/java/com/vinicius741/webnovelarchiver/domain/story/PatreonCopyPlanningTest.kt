package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning.ChapterKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatreonCopyPlanningTest {
    @Test
    fun `parses book and chapter keys from source and Patreon titles`() {
        assertEquals(
            ChapterKey(4, "43", "assembly"),
            PatreonCopyPlanning.chapterKey("The Lich King Reincarnates As A Baby - Book 4 Chapter 43: Assembly"),
        )
        assertEquals(ChapterKey(3, "1", "the next step"), PatreonCopyPlanning.chapterKey("Book 3 - Chapter 1: The Next Step"))
        assertEquals(ChapterKey(3, "10.5", "suffrage"), PatreonCopyPlanning.chapterKey("Book 3 Chapter 10.5: Suffrage"))
        assertEquals(ChapterKey(3, "30", ""), PatreonCopyPlanning.chapterKey("Eat Them All: Book 3- Chapter 30"))
        assertEquals(ChapterKey(null, "7", "a b"), PatreonCopyPlanning.chapterKey("Ch. 07 – A/B"))
    }

    @Test
    fun `a book named after the chapter number does not count as the chapter's book`() {
        assertEquals(
            ChapterKey(null, "62", "an understanding"),
            PatreonCopyPlanning.chapterKey("Chapter 62: An Understanding (End of Book 1)"),
        )
        assertEquals(null, PatreonCopyPlanning.chapterKey("Chapter 123 - Book 2 Epilogue I").book)
    }

    @Test
    fun `matches same book and number, and needs the subtitle when one side omits the book`() {
        val patreon = PatreonCopyPlanning.chapterKey("Novel - Book 4 Chapter 14: Subversion")
        assertTrue(PatreonCopyPlanning.matches(patreon, PatreonCopyPlanning.chapterKey("Book 4 Chapter 14: Subversion")))
        assertTrue(PatreonCopyPlanning.matches(patreon, PatreonCopyPlanning.chapterKey("Chapter 14: Subversion")))
        assertFalse(PatreonCopyPlanning.matches(patreon, PatreonCopyPlanning.chapterKey("Chapter 14: The Doctor Calls")))
        assertFalse(PatreonCopyPlanning.matches(patreon, PatreonCopyPlanning.chapterKey("Book 3 Chapter 14: Subversion")))
        assertFalse(PatreonCopyPlanning.matches(patreon, PatreonCopyPlanning.chapterKey("Book 4 Chapter 15: Subversion")))
    }

    @Test
    fun `unnumbered chapters match on their normalized title`() {
        assertTrue(PatreonCopyPlanning.matches(PatreonCopyPlanning.chapterKey("Prologue"), PatreonCopyPlanning.chapterKey("prologue")))
        assertFalse(PatreonCopyPlanning.matches(PatreonCopyPlanning.chapterKey("Q&A"), PatreonCopyPlanning.chapterKey("Q A")))
    }

    @Test
    fun `display title drops the novel name prefix`() {
        assertEquals(
            "Book 4 Chapter 43: Assembly",
            PatreonCopyPlanning.displayTitle("The Lich King Reincarnates As A Baby - Book 4 Chapter 43: Assembly"),
        )
        assertEquals("Book 3- Chapter 30", PatreonCopyPlanning.displayTitle("Eat Them All: Book 3- Chapter 30"))
        assertEquals("Chapter 5", PatreonCopyPlanning.displayTitle("Chapter 5"))
        assertEquals("Rebirth Chapter 5", PatreonCopyPlanning.displayTitle("Rebirth Chapter 5"))
        assertEquals("Announcement", PatreonCopyPlanning.displayTitle("Announcement"))
    }

    @Test
    fun `recognizes chapter posts and skips announcements`() {
        assertTrue(PatreonCopyPlanning.isChapterPost("Novel - Book 4 Chapter 43: Assembly"))
        assertTrue(PatreonCopyPlanning.isChapterPost("Epilogue"))
        assertFalse(PatreonCopyPlanning.isChapterPost("The Lich King Reincarnates: Book 1 has been published!"))
    }

    @Test
    fun `reconcile drops a copy once its public chapter downloads and moves the bookmark`() {
        val chapters =
            listOf(
                public("1", "Book 4 Chapter 14: Subversion", downloaded = true),
                public("2", "Book 4 Chapter 15: Ascent", downloaded = true),
                public("3", "Book 4 Chapter 16: Burden", downloaded = false),
                copy("p15", "Book 4 Chapter 15: Ascent"),
                copy("p16", "Book 4 Chapter 16: Burden"),
                copy("p17", "Book 4 Chapter 17: Crown"),
            )

        val result = PatreonCopyPlanning.reconcile(chapters, lastReadChapterId = "patreon_p15")

        assertEquals(listOf("1", "2", "3", "patreon_p16", "patreon_p17"), result.chapters.map { it.id })
        assertEquals(listOf("patreon_p15"), result.removed.map { it.id })
        assertEquals("2", result.lastReadChapterId)
        assertEquals("3", result.chapters.first { it.id == "patreon_p16" }.patreonReplacedBy)
        assertNull(result.chapters.first { it.id == "patreon_p17" }.patreonReplacedBy)
    }

    @Test
    fun `reconcile leaves stories without copies untouched`() {
        val chapters = listOf(public("1", "Chapter 1", downloaded = true))
        val result = PatreonCopyPlanning.reconcile(chapters, "1")
        assertEquals(chapters, result.chapters)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `reconcileStory keeps counters, status, and pending ids consistent`() {
        val story =
            Story(
                id = "s",
                chapters =
                    mutableListOf(
                        public("1", "Chapter 1: A", downloaded = true),
                        public("2", "Chapter 2: B", downloaded = true),
                        copy("p2", "Chapter 2: B"),
                    ),
                status = DownloadStatus.partial,
                pendingNewChapterIds = mutableListOf("patreon_p2"),
            )

        val (reconciled, removed) = PatreonCopyPlanning.reconcileStory(story)

        assertEquals(listOf("patreon_p2"), removed.map { it.id })
        assertEquals(2, reconciled.downloadedChapters)
        assertEquals(DownloadStatus.completed, reconciled.status)
        assertNull(reconciled.pendingNewChapterIds)
    }

    @Test
    fun `relinking the same collection keeps copies, switching drops them`() {
        val link = PatreonEarlyAccessLink(campaignId = "c", collectionId = "1", collectionTitle = "Old", lastCheckedAt = 5L)
        val story =
            Story(
                id = "s",
                patreonEarlyAccess = link,
                chapters =
                    mutableListOf(
                        public("1", "Chapter 1", downloaded = true),
                        copy("p2", "Chapter 2").copy(patreonReplacedBy = null),
                    ),
                lastReadChapterId = "patreon_p2",
            )

        val (same, keptRemoved) = PatreonCopyPlanning.relink(story, link.copy(collectionTitle = "Renamed", lastCheckedAt = null))
        assertTrue(keptRemoved.isEmpty())
        assertEquals(2, same.chapters.size)
        assertEquals(5L, same.patreonEarlyAccess?.lastCheckedAt)

        val (unlinked, removed) = PatreonCopyPlanning.relink(story, null)
        assertNull(unlinked.patreonEarlyAccess)
        assertEquals(listOf("patreon_p2"), removed.map { it.id })
        assertEquals(listOf("1"), unlinked.chapters.map { it.id })
        assertEquals("1", unlinked.lastReadChapterId)
        assertEquals(DownloadStatus.completed, unlinked.status)
    }

    private fun public(
        id: String,
        title: String,
        downloaded: Boolean,
    ) = Chapter(id = id, title = title, url = "https://www.royalroad.com/fiction/1/x/chapter/$id/c", downloaded = downloaded)

    private fun copy(
        postId: String,
        title: String,
    ) = Chapter(
        id = PatreonCopyPlanning.chapterId(postId),
        title = title,
        url = PatreonCopyPlanning.postUrl(postId),
        downloaded = true,
        filePath = "novels/s/patreon_$postId.html",
        patreonPostId = postId,
    )
}
