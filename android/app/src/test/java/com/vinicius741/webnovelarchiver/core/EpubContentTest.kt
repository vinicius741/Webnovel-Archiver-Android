package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubContentTest {
    @Test
    fun detailsPageIncludesDescriptionTagsAndEmptyTagPlaceholder() {
        val details = EpubContent.details(
            Story(
                title = "A & B",
                author = "Author",
                description = "Line one\nLine two",
                tags = mutableListOf("Fantasy", "Action & Adventure"),
            ),
        )

        assertTrue(details.contains("<body class=\"details-page\">"))
        assertTrue(details.contains("<p>Line one</p>"))
        assertTrue(details.contains("<p>Line two</p>"))
        assertTrue(details.contains("<span class=\"tag\">Action &amp; Adventure</span>"))

        val noTags = EpubContent.details(Story(title = "No Tags", author = "Author"))
        assertTrue(noTags.contains("No tags available."))
        assertTrue(noTags.contains("No description available."))
    }

    @Test
    fun coverAndCssMatchReaderFriendlyStructure() {
        val cover = EpubContent.cover(Story(title = "Cover Story"), null)
        val css = EpubContent.css()

        assertTrue(cover.contains("<body class=\"cover-page\">"))
        assertTrue(cover.contains("cover-placeholder"))
        assertTrue(css.contains(".cover-image"))
        assertTrue(css.contains(".details-section"))
        assertTrue(css.contains("font-family: sans-serif"))
    }

    @Test
    fun chapterSanitizesFullHtmlDocumentsBeforeEmbedding() {
        val html = EpubContent.chapter(
            Chapter(title = "Chapter <One>"),
            "<html><head><title>Wrong</title></head><body><p>Body</p><div>More</div></body></html>",
        )

        assertTrue(html.contains("<title>Chapter &lt;One&gt;</title>"))
        assertTrue(html.contains("<h2>Chapter &lt;One&gt;</h2>"))
        assertTrue(html.contains("<p>Body</p><div>More</div>"))
        assertTrue(!html.contains("<head><title>Wrong</title></head>"))
        assertTrue(!html.contains("<div class=\"content\"><html>"))
    }

    @Test
    fun sanitizeContentKeepsHtmlFragments() {
        val sanitized = EpubContent.sanitizeContent("<p>Just content</p><blockquote>Quote</blockquote>")

        assertTrue(sanitized.contains("<p>Just content</p>"))
        assertTrue(sanitized.contains("<blockquote>Quote</blockquote>"))
    }

    @Test
    fun sanitizeContentSelfClosesVoidTagsForXhtml() {
        // HTML allows unclosed <br> inside a <span>; XHTML (EPUB's body) does not. This is the exact
        // case that produced "error on line 9 at column 8: Opening and ending tag mismatch: br".
        val sanitized = EpubContent.sanitizeContent("<p><span>First line<br>Second line</span></p>")

        assertTrue("void <br> must be self-closed for XHTML (e.g. <br/> or <br />)", sanitized.contains("<br"))
        assertTrue("self-closing marker must be present", sanitized.contains("/>"))
        assertFalse("unclosed <br> must not survive", sanitized.contains("<br>"))
        assertTrue(sanitized.contains("First line"))
        assertTrue(sanitized.contains("Second line"))
    }

    @Test
    fun sanitizeContentEscapesRawAmpersandsForXhtml() {
        val sanitized = EpubContent.sanitizeContent("<p>Tom &amp; Jerry &amp; Co</p>")

        // Raw ampersand in text must be escaped so the body parses as XML.
        assertFalse(sanitized.contains(" & "))
    }

    @Test
    fun sanitizeContentSelfClosesImgTags() {
        val sanitized = EpubContent.sanitizeContent("<p><img src=\"x.png\" alt=\"pic\"></p>")

        assertTrue("img must be present", sanitized.contains("<img"))
        assertTrue("img must be self-closed for XHTML", sanitized.contains("/>"))
    }

    @Test
    fun chapterFallsBackToPlaceholderForBlankContent() {
        val html = EpubContent.chapter(Chapter(title = "Empty"), "")

        // Empty content must still render valid XHTML, not a blank/broken page. Crucially, source-side
        // parse errors are no longer baked in as text (they now throw — see SourceProvider tests).
        assertTrue(html.contains("This chapter has no readable content."))
        assertFalse(html.contains("No content found"))
    }
}
