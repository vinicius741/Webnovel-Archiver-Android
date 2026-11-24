package com.vinicius741.webnovelarchiver.source.local

import com.vinicius741.webnovelarchiver.domain.sha256Hex
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where a local novel or chapter lives, relative to the chosen local folder. [entry] is the novel's
 * subfolder, or a book file directly in the local folder; [file] is a book file inside that
 * subfolder; [part] picks one chapter out of a book that holds several.
 */
data class LocalAddress(
    val entry: String,
    val file: String? = null,
    val part: String? = null,
)

/**
 * The `local://library/<entry>[/<file>][#<part>]` URLs that local stories and chapters store as
 * [com.vinicius741.webnovelarchiver.domain.model.Story.sourceUrl] and chapter URLs. They name
 * paths relative to the local folder, never a device URI, so choosing the folder again (a new
 * phone, a restored backup) keeps every story and chapter id.
 */
object LocalSourceUrls {
    const val SCHEME = "local"
    const val HOST = "library"
    private const val PREFIX = "$SCHEME://$HOST/"
    private const val STORY_ID_LENGTH = 16
    private const val CHAPTER_ID_LENGTH = 24

    fun story(entry: String): String = PREFIX + encode(entry)

    fun chapter(
        entry: String,
        file: String?,
        part: String?,
    ): String =
        buildString {
            append(story(entry))
            file?.let { append('/').append(encode(it)) }
            part?.let { append('#').append(encode(it)) }
        }

    fun chapter(address: LocalAddress): String = chapter(address.entry, address.file, address.part)

    fun parse(url: String): LocalAddress? {
        val trimmed = url.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = trimmed.substring(PREFIX.length)
        val path = rest.substringBefore('#')
        val part = if ('#' in rest) decode(rest.substringAfter('#')) ?: return null else null
        val segments = path.split('/')
        if (segments.size > 2) return null
        val entry = decode(segments[0])?.takeIf { it.isNotBlank() } ?: return null
        val file = segments.getOrNull(1)?.let { decode(it) ?: return null }?.takeIf { it.isNotEmpty() }
        return LocalAddress(entry, file, part?.takeIf { it.isNotEmpty() })
    }

    /** Stable story id: one per entry name. Renaming the folder makes it a different novel. */
    fun storyId(entry: String): String = "local_" + sha256Hex(entry).take(STORY_ID_LENGTH)

    fun chapterId(address: LocalAddress): String =
        "local_" +
            sha256Hex(listOf(address.entry, address.file.orEmpty(), address.part.orEmpty()).joinToString("\u0000")).take(CHAPTER_ID_LENGTH)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrNull()
}
