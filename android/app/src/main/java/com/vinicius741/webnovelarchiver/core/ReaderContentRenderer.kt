package com.vinicius741.webnovelarchiver.core

object ReaderContentRenderer {
    const val UNDOWNLOADED_CHAPTER_MESSAGE = "Chapter not downloaded yet."

    fun contentOrUndownloadedMessage(content: String?): String =
        content ?: UNDOWNLOADED_CHAPTER_MESSAGE

    fun document(title: String, bodyHtml: String): String =
        document(title, bodyHtml, fontScale = 1.0f, dark = false)

    /**
     * Renders the chapter HTML for the reader WebView. `fontScale` multiplies the base 18px body
     * font-size; `dark` swaps to a dark background + light text (R4 reader chrome).
     */
    fun document(title: String, bodyHtml: String, fontScale: Float, dark: Boolean): String {
        val fontSize = (18f * fontScale.coerceIn(0.5f, 2.5f)).toInt()
        val (bg, fg) = if (dark) "#121212" to "#e6e6e6" else "#ffffff" to "#202124"
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <title>${EpubMetadata.xml(title)}</title>
                <style>
                    body {
                        margin: 0;
                        background-color: $bg;
                        color: $fg;
                        font-family: sans-serif;
                        padding: 20px;
                        line-height: 1.6;
                        font-size: ${fontSize}px;
                        box-sizing: border-box;
                        -webkit-text-size-adjust: 100%;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    p {
                        margin: 0 0 1em 0;
                    }
                </style>
            </head>
            <body>
                $bodyHtml
            </body>
            </html>
        """.trimIndent()
    }
}
