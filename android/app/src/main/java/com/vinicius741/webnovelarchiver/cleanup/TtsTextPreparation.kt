package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import org.jsoup.Jsoup

/**
 * TTS text preparation consumed by the TTS engine and the reader (Maintainability M1: split out of
 * TextCleanup.kt). Produces both the flat chunk list the engine speaks and the chunk-tagged HTML the
 * reader highlights. Kept under `cleanup/` (rather than `tts/`) so it shares the package-private
 * [TextCleanup.regexRunner] with the rest of the cleanup domain. [TextCleanup] re-exposes these for
 * callers that still go through the stateless entry points.
 */
object TtsTextPreparation {
    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val cleanupForDisplay = TextCleanup.regexRunner(regexRules, "download")
        val cleanupForTts = TextCleanup.regexRunner(regexRules, "tts")
        val chunks = mutableListOf<String>()
        var current = ""
        val effectiveChunkSize = chunkSize.coerceAtLeast(100)

        val elements = doc.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
        elements.forEach { element ->
            if (
                element.tagName().equals("div", ignoreCase = true) &&
                element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            ) {
                return@forEach
            }

            val displayText = cleanupForDisplay(element.text()).replace(Regex("\\s+"), " ").trim()
            if (displayText.isBlank()) return@forEach

            val ttsText = cleanupForTts(displayText).replace(Regex("\\s+"), " ").trim()
            if (ttsText.isBlank()) return@forEach

            if (current.isNotBlank() && current.length + 1 + ttsText.length > effectiveChunkSize) {
                chunks.add(current)
                current = ""
            }
            current = if (current.isBlank()) ttsText else "$current $ttsText"
        }

        if (current.isNotBlank()) {
            chunks.add(current)
        }

        if (chunks.isNotEmpty()) return chunks

        val fallback =
            cleanupForTts(cleanupForDisplay(doc.body().text()))
                .replace(Regex("\\s+"), " ")
                .trim()
        return if (fallback.isBlank()) emptyList() else fallback.chunked(effectiveChunkSize)
    }

    /** Result of [prepareTtsAnnotatedHtml]: the chunk-tagged HTML plus the aligned chunk list. */
    data class TtsAnnotatedHtml(
        val annotatedHtml: String,
        val chunks: List<String>,
    )

    /**
     * Produces TTS-aware reader HTML (parity gap 3): a port of the legacy RN `prepareTTSContent`.
     *
     * Walks the same block elements as [prepareTtsChunks] using the identical grouping rule, so each
     * contributing element is tagged with `data-tts-group="<index>"` + the `tts-chunk` class, and the
     * returned [chunks] list is byte-for-byte aligned with [prepareTtsChunks]'s output — meaning a
     * chunk index the engine reports (e.g. via [TtsEngine.playFromChunk] / the playback snapshot)
     * maps 1:1 to the `data-tts-group` the reader's highlight script highlights.
     *
     * When no block elements match, falls back to escaped inline spans with matching group indices,
     * so plain-text fragments still highlight and support tap-to-start.
     */
    fun prepareTtsAnnotatedHtml(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): TtsAnnotatedHtml {
        if (html.isBlank()) return TtsAnnotatedHtml("", emptyList())

        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val cleanupForDisplay = TextCleanup.regexRunner(regexRules, "download")
        val cleanupForTts = TextCleanup.regexRunner(regexRules, "tts")
        val chunks = mutableListOf<String>()
        var current = ""
        var groupIndex = 0
        val effectiveChunkSize = chunkSize.coerceAtLeast(100)
        var taggedAny = false

        val elements = doc.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
        elements.forEach { element ->
            if (
                element.tagName().equals("div", ignoreCase = true) &&
                element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            ) {
                return@forEach
            }

            val displayText = cleanupForDisplay(element.text()).replace(Regex("\\s+"), " ").trim()
            if (displayText.isBlank()) return@forEach

            val ttsText = cleanupForTts(displayText).replace(Regex("\\s+"), " ").trim()
            if (ttsText.isBlank()) return@forEach

            if (current.isNotBlank() && current.length + 1 + ttsText.length > effectiveChunkSize) {
                chunks.add(current)
                current = ""
                groupIndex += 1
            }
            // Tag this element with the group its text belongs to, so the reader's highlight script
            // can resolve "currently speaking group N" back to a DOM node.
            element.attr("data-tts-group", groupIndex.toString())
            element.addClass("tts-chunk")
            taggedAny = true

            current = if (current.isBlank()) ttsText else "$current $ttsText"
        }

        if (current.isNotBlank()) {
            chunks.add(current)
        }

        if (taggedAny) {
            return TtsAnnotatedHtml(doc.body().html(), chunks)
        }

        // Fallback (mirrors prepareTtsChunks): no block elements contributed — collapse to body text.
        val fallback =
            cleanupForTts(cleanupForDisplay(doc.body().text()))
                .replace(Regex("\\s+"), " ")
                .trim()
        return if (fallback.isBlank()) {
            TtsAnnotatedHtml(doc.body().html(), emptyList())
        } else {
            val fallbackChunks = fallback.chunked(effectiveChunkSize)
            val fallbackDoc = Jsoup.parseBodyFragment("")
            fallbackChunks.forEachIndexed { index, chunk ->
                fallbackDoc
                    .body()
                    .appendElement("span")
                    .attr("data-tts-group", index.toString())
                    .addClass("tts-chunk")
                    .text(chunk)
                if (index < fallbackChunks.lastIndex) {
                    fallbackDoc.body().appendText(" ")
                }
            }
            TtsAnnotatedHtml(fallbackDoc.body().html(), fallbackChunks)
        }
    }
}
