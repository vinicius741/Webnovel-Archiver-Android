package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsActionPlanningTest {
    private fun story(
        downloaded: Int,
        total: Int,
        bookmark: Int? = null,
    ): Story =
        Story(
            id = "s",
            title = "Story",
            chapters = MutableList(total) { Chapter(id = "c$it", title = "Chapter ${it + 1}", downloaded = it < downloaded) },
            lastReadChapterId = bookmark?.let { "c$it" },
        )

    @Test
    fun `reading ahead of the downloads continues from the bookmark`() {
        val plan = DetailsActionPlanning.plan(story(downloaded = 10, total = 20, bookmark = 2), canDownload = true)
        assertEquals(DetailsActionPlanning.ReadTarget("c2", 3, resume = true), plan.read)
        assertEquals(10, plan.remaining)
        assertFalse(plan.downloadFirst)
        assertEquals("Continue reading · Ch. 3", DetailsActionPlanning.readLabel(plan.read!!))
        assertEquals("Download 10 remaining", DetailsActionPlanning.downloadLabel(plan))
    }

    @Test
    fun `caught up at the last download puts downloading first`() {
        val plan = DetailsActionPlanning.plan(story(downloaded = 10, total = 20, bookmark = 9), canDownload = true)
        assertTrue(plan.downloadFirst)
        assertEquals("Continue · Ch. 10", DetailsActionPlanning.readLabel(plan.read!!, short = true))
    }

    @Test
    fun `without a bookmark it starts at the first downloaded chapter`() {
        val plan = DetailsActionPlanning.plan(story(downloaded = 20, total = 20), canDownload = true)
        assertEquals(DetailsActionPlanning.ReadTarget("c0", 1, resume = false), plan.read)
        assertEquals(0, plan.remaining)
        assertFalse(plan.downloadFirst)
        assertEquals("Start reading · Ch. 1", DetailsActionPlanning.readLabel(plan.read!!))
    }

    @Test
    fun `nothing downloaded offers only the download`() {
        val plan = DetailsActionPlanning.plan(story(downloaded = 0, total = 5), canDownload = true)
        assertNull(plan.read)
        assertTrue(plan.downloadFirst)
        assertEquals("Download all 5", DetailsActionPlanning.downloadLabel(plan))
    }

    @Test
    fun `a bookmark on a missing chapter reads the next downloaded one`() {
        val chapters =
            mutableListOf(
                Chapter(id = "a", downloaded = true),
                Chapter(id = "b", downloaded = false),
                Chapter(id = "c", downloaded = true),
            )
        val plan = DetailsActionPlanning.plan(Story(id = "s", chapters = chapters, lastReadChapterId = "b"), canDownload = false)
        assertEquals(DetailsActionPlanning.ReadTarget("c", 3, resume = true), plan.read)
        assertEquals(0, plan.remaining)
        assertFalse(plan.downloadFirst)
    }
}
