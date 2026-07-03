package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevLaunchPlanningTest {
    @Test
    fun resolveReturnsNullForMissingOrBlankToken() {
        val provider = throwingProvider()
        assertNull(DevLaunchPlanning.resolve(null, null, null, provider))
        assertNull(DevLaunchPlanning.resolve("", null, null, provider))
        assertNull(DevLaunchPlanning.resolve("   ", null, null, provider))
    }

    @Test
    fun resolveReturnsNullForUnknownToken() {
        assertNull(DevLaunchPlanning.resolve("nope", null, null, throwingProvider()))
    }

    @Test
    fun noArgScreensResolveWithoutReadingTheLibrary() {
        // A provider that throws proves resolve did not touch the library for no-arg screens.
        val provider = throwingProvider()
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Library,
            DevLaunchPlanning.resolve("library", null, null, provider),
        )
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Queue,
            DevLaunchPlanning.resolve("queue", null, null, provider),
        )
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Settings,
            DevLaunchPlanning.resolve("settings", null, null, provider),
        )
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Updates,
            DevLaunchPlanning.resolve("updates", null, null, provider),
        )
        assertEquals(
            DevLaunchPlanning.DevStartTarget.AddStory,
            DevLaunchPlanning.resolve("addstory", null, null, provider),
        )
    }

    @Test
    fun tokensAreCaseInsensitiveAndTrimmed() {
        val provider = throwingProvider()
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Queue,
            DevLaunchPlanning.resolve("Queue", null, null, provider),
        )
        assertEquals(
            DevLaunchPlanning.DevStartTarget.Settings,
            DevLaunchPlanning.resolve("  SETTINGS  ", null, null, provider),
        )
    }

    @Test
    fun readerReturnsNullWhenLibraryIsEmpty() {
        assertNull(
            DevLaunchPlanning.resolve("reader", null, null, { emptyList() }),
        )
    }

    @Test
    fun readerReturnsNullWhenStoryHasNoChapters() {
        val story = story("s1", "Story One", chapters = emptyList())
        assertNull(DevLaunchPlanning.resolve("reader", null, null, { listOf(story) }))
    }

    @Test
    fun readerPicksFirstStoryAndFirstChapterByDefault() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"), chapter("c2", "Chapter 2"))),
                story("s2", "Story Two", listOf(chapter("c3", "Chapter 3"))),
            )
        val target = DevLaunchPlanning.resolve("reader", null, null, { library })
        assertTrue(target is DevLaunchPlanning.DevStartTarget.Reader)
        target as DevLaunchPlanning.DevStartTarget.Reader
        assertEquals("s1", target.storyId)
        assertEquals("c1", target.chapterId)
    }

    @Test
    fun readerHonorsValidStoryAndChapterOverrides() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"), chapter("c2", "Chapter 2"))),
            )
        val target =
            DevLaunchPlanning.resolve("reader", "s1", "c2") { library }
        target as DevLaunchPlanning.DevStartTarget.Reader
        assertEquals("s1", target.storyId)
        assertEquals("c2", target.chapterId)
    }

    @Test
    fun readerReturnsNullWhenStoryOverrideIsUnknown() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"))),
            )
        val target =
            DevLaunchPlanning.resolve("reader", "does-not-exist", null) { library }
        assertNull(target)
    }

    @Test
    fun readerReturnsNullWhenChapterOverrideIsUnknown() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"))),
            )
        val target =
            DevLaunchPlanning.resolve("reader", null, "zzz") { library }
        assertNull(target)
    }

    @Test
    fun detailsPicksFirstStoryByDefault() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"))),
                story("s2", "Story Two", listOf(chapter("c2", "Chapter 2"))),
            )
        val target = DevLaunchPlanning.resolve("details", null, null) { library }
        assertTrue(target is DevLaunchPlanning.DevStartTarget.Details)
        assertEquals("s1", (target as DevLaunchPlanning.DevStartTarget.Details).storyId)
    }

    @Test
    fun detailsHonorsValidStoryOverride() {
        val library =
            listOf(
                story("s1", "Story One", listOf(chapter("c1", "Chapter 1"))),
                story("s2", "Story Two", listOf(chapter("c2", "Chapter 2"))),
            )
        val target = DevLaunchPlanning.resolve("details", "s2", null) { library }
        assertEquals("s2", (target as DevLaunchPlanning.DevStartTarget.Details).storyId)
    }

    @Test
    fun detailsReturnsNullWhenLibraryIsEmpty() {
        assertNull(DevLaunchPlanning.resolve("details", null, null) { emptyList() })
    }

    /**
     * A provider that throws if invoked. Used to prove [DevLaunchPlanning.resolve] does not read the
     * library for no-arg screens — if it did, the test would fail on this assertion.
     */
    private fun throwingProvider(): () -> List<Story> =
        {
            throw AssertionError("libraryProvider must not be invoked for this resolve() call")
        }

    private fun story(
        id: String,
        title: String,
        chapters: List<Chapter>,
    ) = Story(
        id = id,
        title = title,
        chapters = chapters.toMutableList(),
    )

    private fun chapter(
        id: String,
        title: String,
    ) = Chapter(id = id, title = title)
}
