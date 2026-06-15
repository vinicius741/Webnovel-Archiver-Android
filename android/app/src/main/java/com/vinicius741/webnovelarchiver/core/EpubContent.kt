package com.vinicius741.webnovelarchiver.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

object EpubContent {
    fun cover(story: Story, coverHref: String?): String {
        val body = if (coverHref != null) {
            """<img class="cover-image" src="${EpubMetadata.xml(coverHref)}" alt="${EpubMetadata.xml(story.title)} cover"/>"""
        } else {
            """<div class="cover-placeholder"><p>No cover image available</p></div>"""
        }
        return xhtml("${story.title} Cover", """<body class="cover-page"><div class="cover-frame">$body</div></body>""")
    }

    fun details(story: Story): String {
        val description = story.description?.trim()?.takeIf { it.isNotBlank() }
        val descriptionContent = description
            ?.split(Regex("\n+"))
            ?.joinToString("\n") { "<p>${EpubMetadata.xml(it)}</p>" }
            ?: """<p class="muted">No description available.</p>"""
        val tags = story.tags.orEmpty().filter { it.isNotBlank() }
        val tagsContent = if (tags.isEmpty()) {
            """<p class="muted">No tags available.</p>"""
        } else {
            """<div class="tags">${tags.joinToString("\n") { """<span class="tag">${EpubMetadata.xml(it)}</span>""" }}</div>"""
        }
        return xhtml(
            "${story.title} Details",
            """<body class="details-page"><h1>${EpubMetadata.xml(story.title)}</h1><p class="byline">by ${EpubMetadata.xml(story.author)}</p><div class="details-section"><h2>Description</h2><div class="description">$descriptionContent</div></div><div class="details-section"><h2>Tags</h2>$tagsContent</div></body>""",
        )
    }

    fun tableOfContents(chapters: List<Chapter>): String =
        xhtml(
            "Table of Contents",
            """<body><h1>Table of Contents</h1><ul>${chapters.mapIndexed { index, chapter -> """<li><a href="chapter_${index + 1}.xhtml">${EpubMetadata.xml(chapter.title)}</a></li>""" }.joinToString("\n")}</ul></body>""",
        )

    fun chapter(chapter: Chapter, content: String): String {
        val sanitized = sanitizeContent(content)
        // Fall back to a neutral placeholder so an empty chapter still renders as valid XHTML
        // rather than a blank/broken page. Source-side parse failures are now surfaced as
        // download-job errors (see SourceProvider.parseChapterContent), never baked into the EPUB.
        val body = sanitized.ifBlank { """<p class="muted">This chapter has no readable content.</p>""" }
        return xhtml(
            chapter.title,
            """<body><h2>${EpubMetadata.xml(chapter.title)}</h2><div class="content">$body</div></body>""",
        )
    }

    fun sanitizeContent(html: String): String {
        if (html.isBlank()) return ""
        // Chapter content comes from the source sites as HTML, but the EPUB embeds it inside an
        // XHTML 1.1 document that EPUB readers parse with a strict XML parser. Unclosed void tags
        // (`<br>`, `<img>`), raw ampersands, and other HTML-only constructs produce XML errors like
        // "Opening and ending tag mismatch". Round-trip through Jsoup and re-emit with XML syntax so
        // the fragment is always well-formed XHTML (self-closing void tags, escaped entities).
        val doc = Jsoup.parse(html)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)
        val body = doc.body() ?: return ""
        return body.childNodes().joinToString("") { node ->
            when (node) {
                is Element -> node.outerHtml()
                is TextNode -> node.outerHtml()
                else -> node.outerHtml()
            }
        }
    }

    fun css(): String = """
body { color: #1f2933; font-family: sans-serif; margin: 1em; }
h1, h2 { border-bottom: 1px solid #ccc; padding-bottom: 0.5em; text-align: center; }
p { line-height: 1.6; margin-bottom: 1em; }
ul { list-style-type: none; padding: 0; }
li { margin-bottom: 0.5em; }
a { color: #000; text-decoration: none; }
a:hover { text-decoration: underline; }
.cover-page { margin: 0; text-align: center; }
.cover-frame { height: 96vh; line-height: 96vh; text-align: center; width: 100%; }
.cover-image { max-height: 96vh; max-width: 100%; vertical-align: middle; }
.cover-placeholder { border: 1px solid #c8d0d8; line-height: 1.6; margin: 20vh auto 0; padding: 2em; width: 70%; }
.details-page { margin: 2em auto; max-width: 42em; }
.details-page h1 { border-bottom: 0; margin-bottom: 0.2em; }
.byline { color: #5b6773; font-size: 0.95em; margin-top: 0; text-align: center; }
.details-section { border-top: 1px solid #d9dee4; margin-top: 2em; padding-top: 1em; }
.details-section h2 { border-bottom: 0; font-size: 1.2em; margin-bottom: 0.8em; text-align: left; }
.description p { margin-top: 0; text-align: justify; }
.tags { line-height: 2.2; }
.tag { background: #eef2f7; border: 1px solid #c8d0d8; border-radius: 0.35em; display: inline-block; margin: 0 0.4em 0.5em 0; padding: 0.25em 0.65em; }
.muted { color: #6b7280; font-style: italic; }
""".trimIndent()

    private fun xhtml(title: String, body: String): String =
        """<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${EpubMetadata.xml(title)}</title><link href="style.css" type="text/css" rel="stylesheet"/></head>$body</html>"""
}
