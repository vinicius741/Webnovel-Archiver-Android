package com.vinicius741.webnovelarchiver.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentRendererTest {
    @Test
    fun usesUndownloadedChapterMessage() {
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
        assertTrue(html.contains("color: inherit !important"))
        assertTrue(html.contains("background-color: transparent !important"))
        assertTrue(html.contains("padding: 12px 12px 72px 12px"))
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

    @Test
    fun themedColorsApplyToReaderDocument() {
        val html =
            ReaderContentRenderer.document(
                "T",
                "<p>x</p>",
                fontScale = 1.0f,
                colors =
                    ReaderContentRenderer.ReaderDocumentColors(
                        background = "#0D1117",
                        foreground = "#C9D1D9",
                    ),
            )

        assertTrue(html.contains("background-color: #0D1117"))
        assertTrue(html.contains("color: #C9D1D9"))
    }

    @Test
    fun ttsHighlightCssUsesConfiguredAccent() {
        // Gap 3: when the document is rendered with a highlight accent, the .tts-active rule must
        // carry that accent as a translucent background tint.
        val html =
            ReaderContentRenderer.document(
                "T",
                "<p>x</p>",
                fontScale = 1.0f,
                colors =
                    ReaderContentRenderer.ReaderDocumentColors(
                        background = "#000000",
                        foreground = "#FFFFFF",
                        ttsHighlight = "#7C4DFF",
                    ),
                includeTtsScript = true,
            )

        assertTrue(html.contains("#7C4DFF33"))
        assertFalse(html.contains("border-left"))
    }

    @Test
    fun includeTtsScriptInjectsTheWnaTtsBridge() {
        // Gap 3: the highlight + tap-to-start script is only injected when requested.
        val withScript =
            ReaderContentRenderer.document(
                "T",
                "<p>x</p>",
                1.0f,
                ReaderContentRenderer.ReaderDocumentColors("#000000", "#FFFFFF"),
                includeTtsScript = true,
            )
        val withoutScript =
            ReaderContentRenderer.document(
                "T",
                "<p>x</p>",
                1.0f,
                ReaderContentRenderer.ReaderDocumentColors("#000000", "#FFFFFF"),
                includeTtsScript = false,
            )

        assertTrue(withScript.contains("WnaTts"))
        assertTrue(withScript.contains("AndroidBridge"))
        assertTrue(withScript.contains("data-tts-group"))
        assertFalse(withoutScript.contains("WnaTts"))
    }

    @Test
    fun tapToStartScriptResolvesThePluralDataTtsGroupsAttribute() {
        // Regression: the TTS annotation pass emits `data-tts-groups="0 1 …"` (no singular
        // `data-tts-group`) on multi-sentence elements whose TTS-only cleanup changed the sentence
        // count. The highlight selector matches the plural attribute, so the tap-to-start read-back
        // path must too — otherwise tap silently no-ops on those paragraphs.
        val html =
            ReaderContentRenderer.document(
                "T",
                "<p>x</p>",
                1.0f,
                ReaderContentRenderer.ReaderDocumentColors("#000000", "#FFFFFF"),
                includeTtsScript = true,
            )

        // findTtsGroup must fall back to the plural attribute and return its first token.
        assertTrue(html.contains("node.dataset.ttsGroups"))
        assertTrue(html.contains("tokens[0]"))
    }
}
