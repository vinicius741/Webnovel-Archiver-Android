package com.vinicius741.webnovelarchiver.core

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
}
