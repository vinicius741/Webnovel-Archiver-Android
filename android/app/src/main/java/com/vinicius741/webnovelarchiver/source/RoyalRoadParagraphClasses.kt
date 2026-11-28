package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.cleanup.ChapterMarkupCompactor
import java.util.Base64

/**
 * Royal Road gives every chapter paragraph a random class, Base64 of `rs` + hex digits, as
 * anti-piracy noise. Nothing styles or reads it, yet it is about 22% of a stored chapter. This
 * drops only those paragraph classes: the notice marker on `<span>` (Base64 of `r` + 32 hex, see
 * [RoyalRoadNoticeMarker]) and every other class stay. Scans Jsoup-serialized HTML as text.
 */
internal object RoyalRoadParagraphClasses : ChapterMarkupCompactor {
    private const val OPENING = "<p class=\""

    // The lookahead keeps a run-on attribute (`class="…"style=…`, never written by Jsoup) a single tag.
    private val paragraphClass = Regex("""<p class="([A-Za-z0-9+/]{40,48}={0,2})"(?=[\s>/])""")
    private val decodedNoise = Regex("rs[0-9a-f]{16,64}")

    override fun compact(html: String): String {
        if (!html.contains(OPENING)) return html
        var dropped = false
        val compacted =
            paragraphClass.replace(html) { match ->
                if (isNoise(match.groupValues[1])) {
                    dropped = true
                    "<p"
                } else {
                    match.value
                }
            }
        return if (dropped) compacted else html
    }

    private fun isNoise(value: String): Boolean =
        runCatching { String(Base64.getDecoder().decode(value), Charsets.ISO_8859_1) }
            .getOrNull()
            ?.let(decodedNoise::matches) == true
}
