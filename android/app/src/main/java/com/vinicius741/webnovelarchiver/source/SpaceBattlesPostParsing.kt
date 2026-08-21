package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.cleanup.LooseHtmlStructure
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Post-level HTML parsing for SpaceBattles chapters: locating a single post inside a reader or
// thread page and sanitizing its bbCode content for archival. Split out of SpaceBattlesProvider
// so the provider stays the source-policy layer.

private val SPACEBATTLES_POST_ID = Regex("""(?:/posts/|#post-|/post-)(\d+)""", RegexOption.IGNORE_CASE)

internal fun rawPostId(value: String): String? = SPACEBATTLES_POST_ID.find(value)?.groupValues?.get(1)

internal fun parseSpaceBattlesPost(
    html: String,
    postId: String,
    pageUrl: String,
): String? {
    val doc = Jsoup.parse(html, pageUrl)
    val article =
        doc.selectFirst("article.message--post[data-content=post-$postId], article#js-post-$postId")
            ?: return null
    val content =
        article.selectFirst(".message-userContent .message-body .bbWrapper, .message-body .bbWrapper")
            ?: return null
    return sanitizeSpaceBattlesPost(content)
}

internal fun sanitizeSpaceBattlesPost(source: Element): String {
    val content = source.clone()
    content.select("script, style, noscript, iframe, .bbCodeBlock-expandLink, .bbCodeBlock-shrinkLink").remove()
    content.select("img").forEach { image ->
        image
            .attr("data-url")
            .ifBlank { image.attr("data-src") }
            .takeIf(String::isNotBlank)
            ?.let { image.attr("src", it) }
        safeAbsoluteUrl(image, "src")?.let { image.attr("src", it) }
    }
    content.select("a[href]").forEach { link ->
        safeAbsoluteUrl(link, "href")?.let { link.attr("href", it) }
    }
    LooseHtmlStructure.wrapBreakSeparatedParagraphs(content)
    return content.html().trim()
}
