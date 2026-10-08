package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.nodes.Entities

/**
 * Converts Patreon's rich-text post document (`content_json_string`, a ProseMirror tree) into the
 * plain chapter HTML the reader, TTS, and EPUB pipeline already consume. Images and embeds are
 * dropped like source chapter images; unknown nodes keep their text.
 */
object PatreonRichText {
    fun toHtml(json: String): String {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf(JsonElement::isJsonObject) ?: return ""
        return buildString { appendNode(root.asJsonObject) }.trim()
    }

    private fun StringBuilder.appendNode(node: JsonObject) {
        when (node.type()) {
            "text" -> appendText(node)
            "hardBreak" -> append("<br/>")
            "horizontalRule" -> append("<hr/>")
            "image", "embed", "video", "audio" -> Unit
            "paragraph" -> wrap("p", node)
            "heading" -> wrap("h${node.attrs()?.int("level")?.coerceIn(1, 6) ?: 2}", node)
            "blockquote" -> wrap("blockquote", node)
            "bulletList" -> wrap("ul", node)
            "orderedList" -> wrap("ol", node)
            "listItem" -> wrap("li", node)
            else -> appendChildren(node)
        }
    }

    private fun StringBuilder.wrap(
        tag: String,
        node: JsonObject,
    ) {
        append('<').append(tag).append('>')
        appendChildren(node)
        append("</").append(tag).append('>')
    }

    private fun StringBuilder.appendChildren(node: JsonObject) {
        node.getAsJsonArray("content")?.forEach { child ->
            if (child.isJsonObject) appendNode(child.asJsonObject)
        }
    }

    private fun StringBuilder.appendText(node: JsonObject) {
        val text = node.get("text")?.takeUnless(JsonElement::isJsonNull)?.asString ?: return
        val tags =
            node
                .getAsJsonArray("marks")
                ?.mapNotNull { mark ->
                    when (mark.asJsonObject.type()) {
                        "bold" -> "strong"
                        "italic" -> "em"
                        "underline" -> "u"
                        "strike" -> "s"
                        else -> null
                    }
                }.orEmpty()
        tags.forEach { append('<').append(it).append('>') }
        append(Entities.escape(text))
        tags.asReversed().forEach { append("</").append(it).append('>') }
    }

    private fun JsonObject.type(): String? = get("type")?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.attrs(): JsonObject? = get("attrs")?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.int(key: String): Int? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asInt
}
