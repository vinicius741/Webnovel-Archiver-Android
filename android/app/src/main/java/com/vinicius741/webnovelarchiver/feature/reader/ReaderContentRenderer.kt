package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.epub.EpubMetadata

object ReaderContentRenderer {
    const val UNDOWNLOADED_CHAPTER_MESSAGE = "Chapter not downloaded yet."

    data class ReaderDocumentColors(
        val background: String,
        val foreground: String,
        /** Accent color (CSS hex, e.g. "#7C4DFF") for the TTS highlight + left border. Defaults to a
         *  neutral purple so the highlight is visible on both light + dark reader backgrounds when no
         *  theme accent is supplied. */
        val ttsHighlight: String = "#7C4DFF",
    )

    fun contentOrUndownloadedMessage(content: String?): String = content ?: UNDOWNLOADED_CHAPTER_MESSAGE

    fun document(
        title: String,
        bodyHtml: String,
    ): String = document(title, bodyHtml, fontScale = 1.0f, dark = false)

    /**
     * Renders the chapter HTML for the reader WebView. `fontScale` multiplies the base 18px body
     * font-size; `dark` swaps to a dark background + light text (R4 reader chrome).
     */
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
     * Full reader document (parity gap 3). When [includeTtsScript] is true, the body HTML is assumed
     * to have already been through [TextCleanup.prepareTtsAnnotatedHtml] (so block elements carry
     * `data-tts-group` indices) and a small, self-contained script is injected that (a) exposes
     * `WnaTts.setActive(index)` to toggle the `.tts-active` highlight on the speaking chunk, and
     * (b) listens for a double-tap on a tagged paragraph to ask the host to start TTS there via the
     * `AndroidBridge.onTtsStart(index)` JavascriptInterface (attached by [ReaderScreen]).
     *
     * The CSS `.tts-active` rule mirrors the legacy RN reader's highlight: a translucent accent
     * background + a solid accent left border.
     */
    fun document(
        title: String,
        bodyHtml: String,
        fontScale: Float,
        colors: ReaderDocumentColors,
        includeTtsScript: Boolean,
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
                        padding: 12px;
                        line-height: 1.6;
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
                        background-color: ${highlight}33 !important;
                        border-left: 3px solid $highlight;
                        padding-left: 4px;
                    }
                </style>
            </head>
            <body>
                $bodyHtml
                ${if (includeTtsScript) ttsHighlightScript() else ""}
            </body>
            </html>
            """.trimIndent()
    }

    /**
     * The injected reader script (parity gap 3). `WnaTts.setActive(i)` clears the previous
     * `.tts-active` and applies it to every `[data-tts-group="i"]` node, scrolling the first into
     * view. A double-tap (or two quick taps) on a tagged paragraph posts the group index to the
     * host's `AndroidBridge.onTtsStart(i)`. The bridge is a single method attached only while the
     * reader screen is alive, and the script trusts no other page input.
     */
    private fun ttsHighlightScript(): String =
        """
        <script>
            (function() {
                var WnaTts = window.WnaTts = window.WnaTts || {};
                var activeGroup = null;
                WnaTts.setActive = function(index) {
                    try {
                        if (activeGroup !== null) {
                            document.querySelectorAll('.tts-active').forEach(function(node) {
                                node.classList.remove('tts-active');
                            });
                        }
                        activeGroup = (index === null || index === undefined) ? null : String(index);
                        if (activeGroup === null) return;
                        var nodes = document.querySelectorAll('[data-tts-group="' + activeGroup + '"]');
                        nodes.forEach(function(node) { node.classList.add('tts-active'); });
                        if (nodes.length > 0) {
                            nodes[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                        }
                    } catch (e) { /* ignore highlight errors */ }
                };
                function findTtsGroup(target) {
                    var node = target;
                    while (node && node !== document.body) {
                        if (node.dataset && node.dataset.ttsGroup) return node.dataset.ttsGroup;
                        node = node.parentElement;
                    }
                    return null;
                }
                function sendTtsStart(group) {
                    if (group === null) return;
                    if (window.AndroidBridge && typeof window.AndroidBridge.onTtsStart === 'function') {
                        window.AndroidBridge.onTtsStart(Number(group));
                    }
                }
                var lastTapAt = 0, lastTapGroup = null;
                document.addEventListener('dblclick', function(event) {
                    var group = findTtsGroup(event.target);
                    if (group !== null) sendTtsStart(group);
                });
                document.addEventListener('touchend', function(event) {
                    var group = findTtsGroup(event.target);
                    var now = Date.now();
                    if (group !== null && group === lastTapGroup && now - lastTapAt < 350) {
                        sendTtsStart(group);
                        if (event.cancelable) event.preventDefault();
                    }
                    lastTapAt = now;
                    lastTapGroup = group;
                }, { passive: false });
            })();
        </script>
        """.trimIndent()
}
