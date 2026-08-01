package com.vinicius741.webnovelarchiver.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlValidationTest {
    @Test
    fun acceptsSupportedStoryDetailUrls() {
        assertTrue(SourceUrlValidation.isImportableStoryUrl("https://www.royalroad.com/fiction/123/story-title"))
        assertTrue(SourceUrlValidation.isImportableStoryUrl("http://royalroad.com/fiction/123/story-title"))
        assertTrue(SourceUrlValidation.isImportableStoryUrl("https://www.scribblehub.com/series/99/story-title/"))
        assertTrue(
            SourceUrlValidation.isImportableStoryUrl(
                "https://forums.spacebattles.com/threads/phantom-star-original-space-opera.1183048/",
            ),
        )
        assertTrue(
            SourceUrlValidation.isImportableStoryUrl(
                "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            ),
        )
        assertTrue(
            SourceUrlValidation.isImportableStoryUrl(
                "https://m.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            ),
        )
    }

    @Test
    fun rejectsChapterAndNonStoryUrls() {
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.royalroad.com/fiction/123/story/chapter/456/one"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.scribblehub.com/read/99-story/chapter/1000/"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://forums.spacebattles.com/posts/7001/"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.fanfiction.net/u/315314/Silver-Queen"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.fanfiction.net/s/7347955/"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://forums.spacebattles.com/threads/"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.royalroad.com/home"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://example.com/fiction/123/story"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl(""))
    }
}
