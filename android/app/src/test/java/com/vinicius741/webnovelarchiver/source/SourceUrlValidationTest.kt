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
    }

    @Test
    fun rejectsChapterAndNonStoryUrls() {
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.royalroad.com/fiction/123/story/chapter/456/one"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.scribblehub.com/read/99-story/chapter/1000/"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://www.royalroad.com/home"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl("https://example.com/fiction/123/story"))
        assertFalse(SourceUrlValidation.isImportableStoryUrl(""))
    }
}
