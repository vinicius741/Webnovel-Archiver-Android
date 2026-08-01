package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FanFictionProviderTest {
    private fun fixture(path: String): String = javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    @Test
    fun recognizesChapterPageAsImportableStoryAndBuildsStableIds() {
        val url = "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine"
        val mobileUrl = "https://m.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine"

        assertTrue(FanFictionProvider.isSource(url))
        assertTrue(FanFictionProvider.isSource(mobileUrl))
        assertEquals(SourceUrlKind.STORY, FanFictionProvider.classifyUrl(url))
        assertEquals("ffn_7347955", FanFictionProvider.getStoryId(url))
        assertEquals("ffn_7347955_1", FanFictionProvider.getChapterId(url))
        assertEquals(url, FanFictionProvider.normalizeStoryUrl(mobileUrl))
        assertEquals(
            "https://www.fanfiction.net/s/7347955/8/Dreaming-of-Sunshine",
            FanFictionProvider.normalizeStoryUrl(
                "http://m.fanfiction.net/s/7347955/8/Dreaming-of-Sunshine?ref=mobile#top",
            ),
        )
        assertFalse(FanFictionProvider.isSource("https://www.fanfiction.net/u/315314/Silver-Queen"))
        assertFalse(FanFictionProvider.isSource("https://notfanfiction.net/s/7347955/1/title"))
    }

    @Test
    fun parsesMetadataAndNormalizesCanonicalUrlToFirstChapter() {
        val metadata = FanFictionProvider.parseMetadata(fixture("/fixtures/fanfiction/story.html"))

        assertEquals("Dreaming of Sunshine", metadata.title)
        assertEquals("Silver Queen", metadata.author)
        assertEquals("https://www.fanfiction.net/image/1740448/75/", metadata.coverUrl)
        assertEquals("Life as a ninja. It starts with confusion and terror.", metadata.description)
        assertEquals(
            "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            metadata.canonicalUrl,
        )
        assertEquals(PublicationStatus.completed, metadata.publicationStatus)
        assertTrue(metadata.tags.orEmpty().contains("Naruto"))
        assertTrue(metadata.tags.orEmpty().contains("Adventure"))
    }

    @Test
    fun parsesCompleteChapterSelectorWithoutNetworkRequests() =
        runBlocking {
            val progress = mutableListOf<String>()
            val chapters =
                FanFictionProvider.getChapterList(
                    fixture("/fixtures/fanfiction/story.html"),
                    "https://www.fanfiction.net/s/7347955/7/Dreaming-of-Sunshine",
                    NetworkClient(),
                    progress::add,
                )

            assertEquals(3, chapters.size)
            assertEquals(listOf("ffn_7347955_1", "ffn_7347955_2", "ffn_7347955_3"), chapters.map { it.id })
            assertEquals("Prologue", chapters.first().title)
            assertEquals(
                "https://www.fanfiction.net/s/7347955/3/Dreaming-of-Sunshine",
                chapters.last().url,
            )
            assertEquals(1_315_014_342_000L, chapters.first().publishedAt)
            assertEquals(1_553_758_816_000L, chapters.last().publishedAt)
            assertEquals(listOf("Parsing chapter list...", "3 chapters found"), progress)
        }

    @Test
    fun parsesChapterContentAndRemovesScripts() {
        val content = FanFictionProvider.parseChapterContent(fixture("/fixtures/fanfiction/chapter.html"))

        assertTrue(content.contains("My name is Shikako Nara"))
        assertTrue(content.contains("<em>emphasis</em>"))
        assertFalse(content.contains("trackReader"))
    }

    @Test
    fun parsesMobileChapterContentContainer() {
        val mobileHtml = fixture("/fixtures/fanfiction/chapter.html").replace("id=\"storytext\"", "id=\"storycontent\"")

        val content = FanFictionProvider.parseChapterContent(mobileHtml)

        assertTrue(content.contains("My name is Shikako Nara"))
    }
}
