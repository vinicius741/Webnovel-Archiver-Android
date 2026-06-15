package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentRendererTest {
    @Test
    fun usesReactNativeUndownloadedChapterMessage() {
        assertEquals("Chapter not downloaded yet.", ReaderContentRenderer.contentOrUndownloadedMessage(null))
        assertEquals("<p>Saved</p>", ReaderContentRenderer.contentOrUndownloadedMessage("<p>Saved</p>"))
    }

    @Test
    fun wrapsChapterHtmlInReaderDocument() {
        val html = ReaderContentRenderer.document("A & B", "<p>Hello <strong>world</strong></p><img src=\"cover.jpg\"/>")

        assertTrue(html.contains("<title>A &amp; B</title>"))
        assertTrue(html.contains("<p>Hello <strong>world</strong></p>"))
        assertTrue(html.contains("font-size: 18px"))
        assertTrue(html.contains("max-width: 100%"))
        assertTrue(html.contains("<meta name=\"viewport\""))
    }

    @Test
    fun fontScaleScalesBaseFontSize() {
        val html = ReaderContentRenderer.document("T", "<p>x</p>", fontScale = 1.5f, dark = false)
        // 18 * 1.5 = 27
        assertTrue(html.contains("font-size: 27px"))
    }

    @Test
    fun darkModeSwapsBackgroundAndForeground() {
        val html = ReaderContentRenderer.document("T", "<p>x</p>", fontScale = 1.0f, dark = true)
        assertTrue(html.contains("background-color: #121212"))
        assertTrue(html.contains("color: #e6e6e6"))
    }
}
