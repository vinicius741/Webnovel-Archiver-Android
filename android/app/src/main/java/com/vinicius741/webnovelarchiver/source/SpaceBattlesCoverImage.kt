package com.vinicius741.webnovelarchiver.source

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Picks a cover image for a SpaceBattles story: prefers the social `og:image`/`twitter:image`, then
 * falls back to the first meaningful image in the starter post. Extracted from
 * [SpaceBattlesProvider] so that file stays within its size budget.
 */
internal fun spaceBattlesCoverUrl(
    doc: Document,
    author: String,
): String? {
    val socialImage =
        doc
            .selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf(::isMeaningfulImageUrl)
    if (socialImage != null) return socialImage

    val starterPost =
        doc
            .select("article.message--post")
            .firstOrNull { it.attr("data-author").equals(author, ignoreCase = true) }
            ?: return null
    return starterPost
        .select(".message-body .bbWrapper img")
        .asSequence()
        .filterNot { image ->
            image.parents().any { parent ->
                parent.hasClass("bbCodeBlock-unfurl") ||
                    parent.hasClass("smilie") ||
                    parent.tagName().equals("blockquote", ignoreCase = true)
            }
        }.mapNotNull(::spaceBattlesImageUrl)
        .firstOrNull(::isMeaningfulImageUrl)
}

private fun spaceBattlesImageUrl(image: Element): String? {
    val width = image.attr("width").toIntOrNull()
    val height = image.attr("height").toIntOrNull()
    if (width != null && height != null && width * height < MIN_COVER_AREA) return null
    return listOf("data-url", "data-src", "src")
        .firstNotNullOfOrNull { attribute -> SpaceBattlesProvider.safeAbsoluteUrl(image, attribute) }
}

private fun isMeaningfulImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return (normalized.startsWith("http://") || normalized.startsWith("https://")) &&
        COVER_IMAGE_EXCLUSIONS.none(normalized::contains)
}

private const val MIN_COVER_AREA = 10_000
private val COVER_IMAGE_EXCLUSIONS =
    setOf(
        "favicon",
        "/avatar/",
        "/smilies/",
        "/emoji/",
        "/data/svg/",
    )
