package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.data.repository.StoryMutations
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryEditPlanningTest {
    private val story =
        Story(
            id = "s1",
            chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2"), Chapter(id = "c3")),
            lastReadChapterId = "c1",
        )

    @Test
    fun aBookmarkTapMovesTheBookmarkOrClearsItOnTheBookmarkedChapter() {
        assertEquals(StoryEdit.Bookmark("c2"), StoryEditPlanning.bookmarkTap(story, "c2"))
        assertEquals(StoryEdit.Bookmark(null), StoryEditPlanning.bookmarkTap(story, "c1"))
    }

    @Test
    fun pendingEditsApplyInTapOrder() {
        val pending =
            PendingStoryEdits()
                .plus(StoryEdit.Bookmark("c2"))
                .plus(StoryEdit.Bookmark("c3"))

        assertEquals("c3", StoryEditPlanning.shown(story, pending)?.lastReadChapterId)
        assertEquals("c1", story.lastReadChapterId)
    }

    @Test
    fun anEditAlreadyPublishedChangesNothing() {
        val pending = PendingStoryEdits().plus(StoryEdit.Bookmark("c1"))

        assertSame(story, StoryEditPlanning.shown(story, pending))
    }

    @Test
    fun aSettledEditStopsShowing() {
        val added = PendingStoryEdits().plus(StoryEdit.Bookmark("c2")).plus(StoryEdit.Bookmark(null))
        val settled = added.settle(added.edits.first().first)

        assertNull(StoryEditPlanning.shown(story, settled)?.lastReadChapterId)
        assertEquals(1, settled.edits.size)
        assertTrue(settled.settle(added.edits.last().first).isEmpty())
        assertSame(story, StoryEditPlanning.shown(story, PendingStoryEdits()))
    }

    @Test
    fun aBookmarkOnAChapterSyncRemovedIsNotShown() {
        assertSame(story, StoryEditPlanning.apply(story, StoryEdit.Bookmark("gone")))
    }

    @Test
    fun bookmarkTapsShowWhatTheRepositoryWrites() {
        // A legacy EPUB start anchored to the old bookmark is cleared by the write; the shown story must match it.
        val anchored =
            story.copy(
                epubConfig =
                    EpubConfig(
                        startAtBookmark = true,
                        rangeStart =
                            1 + story.chapters.indexOfFirst { it.id == "c1" },
                    ),
            )
        var shown: Story = anchored
        var written: Story = anchored
        listOf("c2", "c2", "c3", "c1", "c1").forEach { tapped ->
            shown = StoryEditPlanning.apply(shown, StoryEditPlanning.bookmarkTap(shown, tapped))
            written = checkNotNull(StoryMutations.toggleBookmark(written, tapped))
            assertEquals(written, shown)
        }
    }

    @Test
    fun aiShowTogglesNeedTheAiVersion() {
        assertSame(story, StoryEditPlanning.apply(story, StoryEdit.ShowAiDescription(true)))
        assertSame(story, StoryEditPlanning.apply(story, StoryEdit.ShowAiCover(false)))

        val withAi = story.copy(aiDescription = "AI", aiCoverPath = "covers/s1.png", showAiCover = true)
        assertTrue(StoryEditPlanning.apply(withAi, StoryEdit.ShowAiDescription(true)).showAiDescription)
        assertFalse(StoryEditPlanning.apply(withAi, StoryEdit.ShowAiCover(false)).showAiCover)
        assertSame(withAi, StoryEditPlanning.apply(withAi, StoryEdit.ShowAiCover(true)))
    }
}
