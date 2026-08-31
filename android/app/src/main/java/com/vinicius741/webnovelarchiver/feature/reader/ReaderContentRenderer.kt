package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.epub.EpubMetadata

object ReaderContentRenderer {
    const val UNDOWNLOADED_CHAPTER_MESSAGE = "Chapter not downloaded yet."

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
     * [TtsTextPreparation.prepareTtsAnnotatedHtml] so elements carry `data-tts-group` indices for
     * the injected highlight/tap script.
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
                        /* Extra bottom padding keeps the last line clear of the docked chapter
                           nav bar (~56dp) when scrolled to the end of the chapter. */
                        padding: 12px 12px 72px 12px;
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
     * Injected script: `WnaTts.setActive(i)` toggles `.tts-active` on the speaking chunk and
     * scrolls it into view; a double-tap on a tagged paragraph calls `AndroidBridge.onTtsStart(i)`
     * (bridge attached only while the reader screen is alive).
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
                        var nodes = document.querySelectorAll('[data-tts-group="' + activeGroup + '"], [data-tts-groups~="' + activeGroup + '"]');
                        nodes.forEach(function(node) { node.classList.add('tts-active'); });
                        if (nodes.length > 0) {
                            nodes[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                        }
                    } catch (e) { /* ignore highlight errors */ }
                };
                function findTtsGroup(target) {
                    var node = target;
                    while (node && node !== document.body) {
                        if (node.dataset) {
                            // Single-token tag (set on single-sentence elements, rebuilt per-sentence
                            // spans, and fallback spans): tap resolves to that exact chunk.
                            if (node.dataset.ttsGroup) return node.dataset.ttsGroup;
                            // Multi-token tag (data-tts-groups="0 1 …") is set on multi-sentence
                            // elements whose TTS-only cleanup changed the sentence structure, where
                            // no per-sentence child span exists. Resolve to the first token so the
                            // tap still starts at this element rather than silently no-op'ing.
                            if (node.dataset.ttsGroups) {
                                var tokens = node.dataset.ttsGroups.trim().split(/\s+/);
                                if (tokens.length) return tokens[0];
                            }
                        }
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
