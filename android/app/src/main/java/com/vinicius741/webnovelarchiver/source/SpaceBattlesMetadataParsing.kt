package com.vinicius741.webnovelarchiver.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal fun cleanTag(tag: Element): String? {
    val copy = tag.clone()
    copy.select("i, svg, .u-srOnly").remove()
    return copy.text().trim().takeIf(String::isNotBlank)
}

internal fun sourceStatElement(
    stats: List<Element>,
    label: String,
): Element? =
    stats
        .firstOrNull { stat ->
            normalizedSourceText(stat.selectFirst("dt")?.text()).equals(label, ignoreCase = true)
        }?.children()
        ?.firstOrNull { child -> child.tagName().equals("dd", ignoreCase = true) }

internal fun sourceStatText(
    stats: List<Element>,
    label: String,
): String? =
    sourceStatElement(stats, label)
        ?.text()
        ?.let(::normalizedSourceText)
        ?.takeIf(String::isNotBlank)

internal fun Element.sourceDateMillis(): Long? {
    val time = selectFirst("time")
    val candidates =
        buildList {
            time?.let { node ->
                listOf("unixtime", "data-time", "data-timestamp", "datetime", "title").forEach { attr ->
                    node.attr(attr).takeIf(String::isNotBlank)?.let(::add)
                }
                node.text().takeIf(String::isNotBlank)?.let(::add)
            }
            text().takeIf(String::isNotBlank)?.let(::add)
        }
    return candidates.firstNotNullOfOrNull { candidate -> parseSourceDateMillis(candidate) }
        ?: chapterPublishedAt()
}

internal fun threadPrefix(doc: Document): String? =
    doc
        .selectFirst(
            ".threadmarkListingHeader-prefix a.labelLink, " +
                ".threadmarkListingHeader-prefix .label, " +
                ".p-description a.labelLink, " +
                ".p-description .label, " +
                ".p-title-pageAction a.labelLink, " +
                ".p-title-pageAction .label, " +
                "a.labelLink",
        )?.text()
        ?.let(::normalizedSourceText)
        ?.takeIf(String::isNotBlank)

internal fun threadCategory(doc: Document): String? =
    doc
        .select(".p-breadcrumbs a[href], .breadcrumb a[href]")
        .filter { it.attr("href").contains("/forums/", ignoreCase = true) }
        .mapNotNull { it.text().let(::normalizedSourceText).takeIf(String::isNotBlank) }
        .lastOrNull()

internal fun threadDiscussionState(doc: Document): String? {
    val explicitState =
        doc
            .select(
                "meta[name=discussion_open], meta[name=discussion_state], " +
                    "meta[property=discussion_open], meta[property=discussion_state], " +
                    "[data-discussion-state]",
            ).asSequence()
            .mapNotNull { element ->
                val value =
                    if (element.hasAttr("data-discussion-state")) {
                        element.attr("data-discussion-state")
                    } else {
                        element.attr("content")
                    }
                discussionStateFromText(value)
            }.firstOrNull()
    if (explicitState != null) return explicitState

    val bodyClasses =
        doc
            .body()
            .classNames()
            .joinToString(" ")
    discussionStateFromText(bodyClasses)?.let { return it }

    val visibleStateText =
        doc
            .select(".p-body-header, .p-description, .blockMessage, .messageNotice, .threadmarkListingHeader")
            .text()
    return when {
        Regex("(?i)not open for further replies|closed for further replies|thread is closed")
            .containsMatchIn(visibleStateText) -> "Closed"
        Regex("(?i)open for further replies").containsMatchIn(visibleStateText) -> "Open"
        else -> null
    }
}

private fun discussionStateFromText(value: String?): String? {
    val normalized = normalizedSourceText(value).lowercase()
    return when {
        normalized == "0" ||
            normalized == "false" ||
            normalized == "closed" ||
            normalized == "locked" ||
            normalized.contains("closed") ||
            normalized.contains("locked") -> "Closed"
        normalized == "1" || normalized == "true" || normalized == "open" -> "Open"
        else -> null
    }
}

internal fun isReaderPage(html: String): Boolean {
    if (html.isBlank()) return false
    val doc = Jsoup.parse(html)
    return doc.selectFirst("article.message--post .message-body .bbWrapper") != null
}

internal fun safeAbsoluteUrl(
    element: Element,
    attribute: String,
): String? =
    runCatching { element.absUrl(attribute) }
        .getOrNull()
        .orEmpty()
        .ifBlank { element.attr(attribute) }
        .trim()
        .takeIf(String::isNotBlank)
