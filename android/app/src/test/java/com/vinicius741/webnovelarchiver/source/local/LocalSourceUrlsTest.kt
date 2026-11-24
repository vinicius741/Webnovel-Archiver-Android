package com.vinicius741.webnovelarchiver.source.local

import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LocalSourceUrlsTest {
    @Test
    fun addressesRoundTripThroughUrlsWithAwkwardNames() {
        val address = LocalAddress("Mother of Learning (2)", "Vol 1 + extras #1.epub", "OEBPS/ch 1.xhtml#c2")
        val url = LocalSourceUrls.chapter(address)

        assertEquals(address, LocalSourceUrls.parse(url))
        assertEquals(LocalAddress("Mother of Learning (2)"), LocalSourceUrls.parse(LocalSourceUrls.story(address.entry)))
        assertEquals("local://library/A%20B/c.txt", LocalSourceUrls.chapter("A B", "c.txt", null))
    }

    @Test
    fun rejectsOtherUrls() {
        assertNull(LocalSourceUrls.parse("https://library/a"))
        assertNull(LocalSourceUrls.parse("local://library/"))
        assertNull(LocalSourceUrls.parse("local://library/a/b/c"))
        assertNull(SourceRegistry.resolve("https://library/a"))
    }

    @Test
    fun idsAreStableAndDistinct() {
        assertEquals(LocalSourceUrls.storyId("Novel"), LocalSourceUrls.storyId("Novel"))
        assertNotEquals(LocalSourceUrls.storyId("Novel"), LocalSourceUrls.storyId("Novel 2"))
        assertNotEquals(
            LocalSourceUrls.chapterId(LocalAddress("Novel", "a.txt")),
            LocalSourceUrls.chapterId(LocalAddress("Novel", "a.txt", "2")),
        )
    }

    @Test
    fun registryRoutesLocalUrlsToTheLocalProvider() {
        val story = requireNotNull(SourceRegistry.resolve("local://library/My%20Novel", SourceUrlKind.STORY))
        assertSame(LocalSourceProvider, story.provider)
        assertEquals(LocalSourceUrls.storyId("My Novel"), LocalSourceProvider.getStoryId(story.normalizedUrl))
        val chapter = LocalSourceUrls.chapter("My Novel", "01.txt", null)
        assertEquals(SourceUrlKind.CHAPTER, SourceRegistry.resolve(chapter)?.kind)
        assertEquals(LocalSourceUrls.chapterId(LocalAddress("My Novel", "01.txt")), LocalSourceProvider.getChapterId(chapter))
        assertSame(LocalSourceProvider, SourceRegistry.getById("local"))
    }
}
