package com.vinicius741.webnovelarchiver.feature.reader

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Sanitizes downloaded chapter HTML before it is rendered in the reader WebView (R9, parity gap 3).
 *
 * The reader now runs with JavaScript enabled (required for TTS paragraph highlighting + tap-to-
 * start). Because the chapter HTML comes from an untrusted source (Royal Road / Scribble Hub), it is
 * first cleaned through a strict Jsoup [Safelist]: scripts, event handlers (`on*`), inline styles
 * that can carry payloads, `<iframe>`/`<object>`/`<embed>`, and other active content are stripped,
 * while the prose-bearing tags the reader needs (`p`, headings, `blockquote`, `li`, tables, `img`,
 * `a`, `br`, `span`, `div`) are preserved. `data-tts-group`, `data-tts-groups`, and `class` are
 * explicitly allowed so the TTS annotation pass can tag chunks the highlight script reads back.
 *
 * This is a net hardening over the prior JS-off-but-unsanitized render: even with JS off, unsanitized
 * HTML could carry misleading markup; with sanitization, the only script that ever runs in the reader
 * is the one we inject ourselves.
 */
object ChapterHtmlSanitizer {
    private val safelist: Safelist =
        Safelist
            .relaxed()
            // Preserve the structural classes the reader CSS + TTS annotation rely on.
            .addAttributes(":all", "class")
            // TTS chunk grouping attribute set by [TextCleanup.prepareTtsAnnotatedHtml].
            .addAttributes(":all", "data-tts-group")
            .addAttributes(":all", "data-tts-groups")
            // `dir`/`lang` keep RTL/translated prose readable; both are inert attributes.
            .addAttributes(":all", "dir")
            .addAttributes(":all", "lang")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener")

    /** Cleans [html] of all active content, returning safe body HTML for the reader WebView. */
    fun sanitize(html: String): String {
        if (html.isBlank()) return ""
        // Parse as a body fragment so Jsoup wraps loose text, then clean the full fragment. The
        // body's inner HTML is returned so the reader can inject it into its own <body>.
        val parsed = Jsoup.parseBodyFragment(html)
        val cleaned = Jsoup.clean(parsed.body().html(), safelist) ?: return ""
        return cleaned
    }
}
