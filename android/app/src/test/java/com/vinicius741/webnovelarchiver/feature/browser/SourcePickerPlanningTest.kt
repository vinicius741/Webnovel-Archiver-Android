package com.vinicius741.webnovelarchiver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePickerPlanningTest {
    @Test
    fun hostStripsSchemeAndLeadingWww() {
        assertEquals("royalroad.com", SourcePickerPlanning.host("https://www.royalroad.com"))
        assertEquals("scribblehub.com", SourcePickerPlanning.host("https://www.scribblehub.com/"))
    }

    @Test
    fun hostDropsPathQueryAndFragment() {
        assertEquals(
            "royalroad.com",
            SourcePickerPlanning.host("https://www.royalroad.com/fiction/123/story?tab=chapters#toc"),
        )
    }

    @Test
    fun hostKeepsNonWwwSubdomains() {
        assertEquals("blog.example.com", SourcePickerPlanning.host("https://blog.example.com/posts"))
    }

    @Test
    fun hostFallsBackForBlankOrUnparseableInput() {
        assertEquals("", SourcePickerPlanning.host(""))
        assertEquals("not a url", SourcePickerPlanning.host("not a url"))
    }
}
