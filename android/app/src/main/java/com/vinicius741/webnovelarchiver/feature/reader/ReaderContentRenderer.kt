package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.epub.EpubMetadata

object ReaderContentRenderer {
    const val UNDOWNLOADED_CHAPTER_MESSAGE = "Chapter not downloaded yet."

    /**
     * What the reader shows around the chapter text: a small kicker above it (story · chapter) and,
     * below it, the next-chapter card ([nextChapterTitle]) or a closing line ([endNote]).
     */
    data class ReaderFrame(
        val kicker: String? = null,
        val nextChapterTitle: String? = null,
        val endNote: String? = null,
    )

    data class ReaderDocumentColors(
        val background: String,
        val foreground: String,
        /** CSS hex accent for the TTS highlight; default reads on light + dark backgrounds. */
        val ttsHighlight: String = "#7C4DFF",
    )

    fun contentOrUndownloadedMessage(content: String?): String = content ?: UNDOWNLOADED_CHAPTER_MESSAGE

    fun document(
        title: String,
        bodyHtml: String,
    ): String = document(title, bodyHtml, fontScale = 1.0f, dark = false)

    fun document(
        title: String,
        bodyHtml: String,
        fontScale: Float,
        dark: Boolean,
    ): String {
        val (bg, fg) = if (dark) "#121212" to "#e6e6e6" else "#ffffff" to "#202124"
        return document(title, bodyHtml, fontScale, ReaderDocumentColors(bg, fg))
    }

    fun document(
        title: String,
        bodyHtml: String,
        fontScale: Float,
        colors: ReaderDocumentColors,
    ): String = document(title, bodyHtml, fontScale, colors, includeTtsScript = false)

    /**
     * Full reader document. When [includeTtsScript] is true, [bodyHtml] must already come from
     * [TtsTextPreparation.prepareTtsAnnotatedHtml] so elements carry the `data-tts-groups` sentence
     * indices [ReaderReadAlongScript] highlights. [frame]'s next-chapter card needs that script.
     */
    fun document(
        title: String,
        bodyHtml: String,
        fontScale: Float,
        colors: ReaderDocumentColors,
        includeTtsScript: Boolean,
        frame: ReaderFrame? = null,
    ): String {
        val fontSize = (18f * fontScale.coerceIn(0.5f, 2.5f)).toInt()
        val highlight = colors.ttsHighlight
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <title>${EpubMetadata.xml(title)}</title>
                <style>
                    body {
                        margin: 0;
                        background-color: ${colors.background};
                        color: ${colors.foreground};
                        font-family: sans-serif;
                        /* The docked chapter nav and player sit below the WebView, so the end
                           only needs breathing room. */
                        padding: 8px 10px 40px 10px;
                        line-height: 1.65;
                        font-size: ${fontSize}px;
                        box-sizing: border-box;
                        -webkit-text-size-adjust: 100%;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    body * {
                        color: inherit !important;
                        background-color: transparent !important;
                    }
                    p {
                        margin: 0 0 1em 0;
                    }
                    .tts-active {
                        background-color: ${highlight}38 !important;
                        color: #FFF4D6 !important;
                        border-radius: 4px;
                        box-shadow: 0 0 0 2px ${highlight}38;
                        -webkit-box-decoration-break: clone;
                        box-decoration-break: clone;
                    }
                    .wna-kicker {
                        font-size: 12px;
                        font-weight: 700;
                        letter-spacing: 0.6px;
                        text-transform: uppercase;
                        color: $highlight !important;
                        margin: 12px 0 18px 0;
                    }
                    .wna-end {
                        margin: 32px 0 8px 0;
                        font-size: 15px;
                    }
                    .wna-next {
                        display: block;
                        position: relative;
                        padding: 18px 48px 18px 18px;
                        border-radius: 18px;
                        background-color: ${CARD_FILL} !important;
                        border: 1px solid ${CARD_LINE};
                        cursor: pointer;
                    }
                    .wna-next .wna-muted, .wna-end-note {
                        color: $MUTED !important;
                    }
                    .wna-next b {
                        display: block;
                        font-size: 16px;
                        margin-top: 2px;
                    }
                    .wna-next .wna-arrow {
                        position: absolute;
                        right: 18px;
                        top: 50%;
                        margin-top: -12px;
                        font-size: 20px;
                        line-height: 24px;
                        color: $highlight !important;
                    }
                    .wna-end-note {
                        text-align: center;
                    }
                </style>
            </head>
            <body>
                ${frame?.kicker?.let { "<div class=\"wna-kicker\">${EpubMetadata.xml(it)}</div>" }.orEmpty()}
                $bodyHtml
                ${frame?.let { endHtml(it, includeTtsScript) }.orEmpty()}
                ${if (includeTtsScript) ReaderReadAlongScript.script else ""}
            </body>
            </html>
            """.trimIndent()
    }

    private fun endHtml(
        frame: ReaderFrame,
        hasBridge: Boolean,
    ): String {
        val next = frame.nextChapterTitle
        return when {
            next != null && hasBridge ->
                "<div class=\"wna-end\"><div class=\"wna-next\" role=\"button\" onclick=\"${ReaderReadAlongScript.NEXT_CHAPTER_CALL}\">" +
                    "<span class=\"wna-muted\">Next chapter</span><b>${EpubMetadata.xml(next)}</b>" +
                    "<span class=\"wna-arrow\">&rarr;</span></div></div>"
            frame.endNote != null -> "<div class=\"wna-end\"><div class=\"wna-end-note\">${EpubMetadata.xml(frame.endNote)}</div></div>"
            else -> ""
        }
    }

    private const val CARD_FILL = "#191712"
    private const val CARD_LINE = "#2A261F"
    private const val MUTED = "#AAA190"
}
