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
     * [TtsTextPreparation.prepareTtsAnnotatedHtml] so elements carry `data-tts-group` indices for
     * the injected highlight/tap script. [frame]'s next-chapter card needs that script's bridge.
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
                ${if (includeTtsScript) ttsHighlightScript() else ""}
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
                "<div class=\"wna-end\"><div class=\"wna-next\" role=\"button\" onclick=\"WnaTts.nextChapter()\">" +
                    "<span class=\"wna-muted\">Next chapter</span><b>${EpubMetadata.xml(next)}</b>" +
                    "<span class=\"wna-arrow\">&rarr;</span></div></div>"
            frame.endNote != null -> "<div class=\"wna-end\"><div class=\"wna-end-note\">${EpubMetadata.xml(frame.endNote)}</div></div>"
            else -> ""
        }
    }

    private const val CARD_FILL = "#191712"
    private const val CARD_LINE = "#2A261F"
    private const val MUTED = "#AAA190"

    /**
     * Injected script: `WnaTts.setActive(i)` toggles `.tts-active` on the speaking chunk and
     * scrolls it into view; on load it applies `AndroidBridge.activeTtsGroup()` (the sentence
     * already speaking when the reader opened) without animating; a double-tap on a tagged paragraph calls `AndroidBridge.onTtsStart(i)`
     * (bridge attached only while the reader screen is alive). Dragging the page while a sentence is
     * highlighted stops following it (`AndroidBridge.onFollowChanged(false)`) until `WnaTts.follow()`.
     */
    private fun ttsHighlightScript(): String =
        """
        <script>
            (function() {
                var WnaTts = window.WnaTts = window.WnaTts || {};
                var activeGroup = null;
                var following = true;
                function notifyFollow(value) {
                    try {
                        if (window.AndroidBridge && typeof window.AndroidBridge.onFollowChanged === 'function') {
                            window.AndroidBridge.onFollowChanged(value);
                        }
                    } catch (e) { /* ignore bridge errors */ }
                }
                function activeNodes() {
                    if (activeGroup === null) return [];
                    return document.querySelectorAll('[data-tts-group="' + activeGroup + '"], [data-tts-groups~="' + activeGroup + '"]');
                }
                WnaTts.follow = function() {
                    following = true;
                    notifyFollow(true);
                    var nodes = activeNodes();
                    if (nodes.length > 0) nodes[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                };
                WnaTts.nextChapter = function() {
                    if (window.AndroidBridge && typeof window.AndroidBridge.onNextChapter === 'function') {
                        window.AndroidBridge.onNextChapter();
                    }
                };
                var dragStartY = null;
                document.addEventListener('touchstart', function(event) {
                    dragStartY = event.touches.length ? event.touches[0].clientY : null;
                }, { passive: true });
                document.addEventListener('touchmove', function(event) {
                    if (!following || activeGroup === null || dragStartY === null || !event.touches.length) return;
                    if (Math.abs(event.touches[0].clientY - dragStartY) > 12) {
                        following = false;
                        notifyFollow(false);
                    }
                }, { passive: true });
                WnaTts.setActive = function(index, instant) {
                    try {
                        if (activeGroup !== null) {
                            document.querySelectorAll('.tts-active').forEach(function(node) {
                                node.classList.remove('tts-active');
                            });
                        }
                        activeGroup = (index === null || index === undefined) ? null : String(index);
                        if (activeGroup === null) return;
                        var nodes = activeNodes();
                        nodes.forEach(function(node) { node.classList.add('tts-active'); });
                        if (nodes.length > 0 && (following || instant)) {
                            nodes[0].scrollIntoView({ behavior: instant ? 'auto' : 'smooth', block: 'center' });
                        }
                    } catch (e) { /* ignore highlight errors */ }
                };
                // Highlights sent while the page was loading were dropped: ask for the live one.
                try {
                    if (window.AndroidBridge && typeof window.AndroidBridge.activeTtsGroup === 'function') {
                        var initial = window.AndroidBridge.activeTtsGroup();
                        if (initial >= 0) WnaTts.setActive(initial, true);
                    }
                } catch (e) { /* ignore highlight errors */ }
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
                    if (!following) {
                        following = true;
                        notifyFollow(true);
                    }
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
