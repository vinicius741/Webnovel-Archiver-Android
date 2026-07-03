package com.vinicius741.webnovelarchiver.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterHtmlSanitizerTest {
    @Test
    fun stripsScriptTags() {
        val html = "<p>Chapter text</p><script>alert('xss')</script>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertFalse(cleaned.contains("<script"))
        assertFalse(cleaned.contains("alert"))
        assertTrue(cleaned.contains("Chapter text"))
    }

    @Test
    fun stripsInlineEventHandlers() {
        // on* attributes are how injected HTML smuggles script execution; they must be removed even
        // on tags the safelist keeps.
        val html = "<p onclick=\"steal()\">Text</p><a href=\"https://example.com\" onclick=\"bad()\">link</a>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertFalse("onclick survived", cleaned.contains("onclick"))
        assertTrue(cleaned.contains("Text"))
        assertTrue(cleaned.contains("https://example.com"))
    }

    @Test
    fun stripsIframesAndObjects() {
        val html = "<p>Body</p><iframe src=\"https://evil.example\"></iframe><object data=\"x\"></object>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertFalse(cleaned.contains("<iframe"))
        assertFalse(cleaned.contains("<object"))
        assertTrue(cleaned.contains("Body"))
    }

    @Test
    fun preservesProseStructure() {
        val html = "<h2>Chapter 1</h2><p>First <strong>paragraph</strong>.</p><blockquote>A quote</blockquote><ul><li>one</li></ul>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertTrue(cleaned.contains("<h2>Chapter 1</h2>"))
        assertTrue(cleaned.contains("<strong>paragraph</strong>"))
        assertTrue(cleaned.contains("<blockquote>A quote</blockquote>"))
        assertTrue(cleaned.contains("<li>one</li>"))
    }

    @Test
    fun preservesDataTtsGroupAttributesAndClassAttributes() {
        // The TTS annotation pass sets these on prose elements; the sanitizer must keep them so the
        // reader's highlight script can read them back.
        val html = "<p data-tts-group=\"0\" data-tts-groups=\"0 1\" class=\"tts-chunk\">Highlighted chunk</p>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertTrue(cleaned.contains("data-tts-group=\"0\""))
        assertTrue(cleaned.contains("data-tts-groups=\"0 1\""))
        assertTrue(cleaned.contains("tts-chunk"))
    }

    @Test
    fun preservesImages() {
        val html = "<p>Text</p><img src=\"https://example.com/cover.png\" alt=\"cover\"/>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertTrue(cleaned.contains("<img"))
        assertTrue(cleaned.contains("https://example.com/cover.png"))
    }

    @Test
    fun returnsEmptyForBlankInput() {
        assertTrue(ChapterHtmlSanitizer.sanitize("").isEmpty())
    }

    @Test
    fun javascriptProtocolLinksAreRemoved() {
        val html = "<a href=\"javascript:steal()\">click</a>"
        val cleaned = ChapterHtmlSanitizer.sanitize(html)
        assertFalse(cleaned.contains("javascript:"))
    }
}
