package com.vinicius741.webnovelarchiver.core

object ReaderContentRenderer {
    const val UNDOWNLOADED_CHAPTER_MESSAGE = "Chapter not downloaded yet."

    fun contentOrUndownloadedMessage(content: String?): String =
        content ?: UNDOWNLOADED_CHAPTER_MESSAGE

    fun document(title: String, bodyHtml: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <title>${EpubMetadata.xml(title)}</title>
                <style>
                    body {
                        margin: 0;
                        background-color: #ffffff;
                        color: #202124;
                        font-family: sans-serif;
                        padding: 20px;
                        line-height: 1.6;
                        font-size: 18px;
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
