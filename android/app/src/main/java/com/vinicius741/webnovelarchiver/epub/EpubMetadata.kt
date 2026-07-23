package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

data class EpubCoverMetadata(
    val href: String,
    val mediaType: String,
)

object EpubMetadata {
    fun opf(
        story: Story,
        chapters: List<Chapter>,
        cover: EpubCoverMetadata?,
        chaptersOnly: Boolean = false,
    ): String {
        val manifest =
            chapters
                .mapIndexed { index, _ ->
                    "<item id=\"chapter_${index + 1}\" href=\"chapter_${index + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>"
                }.joinToString("")
        val spine = chapters.mapIndexed { index, _ -> "<itemref idref=\"chapter_${index + 1}\"/>" }.joinToString("")
        val coverItem =
            cover
                ?.let {
                    "<item id=\"cover-image\" href=\"${xml(it.href)}\" media-type=\"${xml(it.mediaType)}\"/>"
                }.orEmpty()
        val coverMeta = cover?.let { "<meta name=\"cover\" content=\"cover-image\" />" }.orEmpty()
        // When chaptersOnly is set, the cover page, description/tags page, and human-readable TOC are
        // all omitted; their manifest items, spine entries, and guide references go with them. The
        // metadata (dc:description/dc:subject) is still emitted so the EPUB stays catalogable.
        val frontMatterItems =
            if (chaptersOnly) {
                ""
            } else {
                "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"details\" href=\"details.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"toc\" href=\"toc.xhtml\" media-type=\"application/xhtml+xml\"/>"
            }
        val frontMatterSpine =
            if (chaptersOnly) {
                ""
            } else {
                "<itemref idref=\"cover\"/><itemref idref=\"details\"/><itemref idref=\"toc\"/>"
            }
        val guide =
            if (chaptersOnly) {
                ""
            } else {
                "<guide><reference type=\"cover\" title=\"Cover\" href=\"cover.xhtml\"/><reference type=\"toc\" title=\"Table of Contents\" href=\"toc.xhtml\"/></guide>"
            }
        val description =
            story.description
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    "<dc:description>${xml(it)}</dc:description>"
                }.orEmpty()
        val subjects = story.tags.orEmpty().joinToString("") { tag -> "<dc:subject>${xml(tag)}</dc:subject>" }

        return """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>${xml(
            story.title,
        )}</dc:title><dc:creator opf:role="aut">${xml(
            story.author,
        )}</dc:creator><dc:language>en</dc:language><dc:identifier id="BookId">urn:webnovel:${xml(
            story.id,
        )}</dc:identifier>$description$subjects$coverMeta<meta name="generator" content="Webnovel Archiver Android" /></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="style" href="style.css" media-type="text/css"/>$frontMatterItems$coverItem$manifest</manifest><spine toc="ncx">$frontMatterSpine$spine</spine>$guide</package>"""
    }

    fun ncx(
        story: Story,
        chapters: List<Chapter>,
        chaptersOnly: Boolean = false,
    ): String {
        // Front matter navPoints only exist when those pages are in the package. When chaptersOnly is
        // set there is no front matter, so chapters begin at playOrder 1; otherwise cover/details/toc
        // occupy playOrder 1-3 and chapters start at 4.
        val frontMatter =
            if (chaptersOnly) {
                ""
            } else {
                listOf(
                    Triple("cover", "Cover", "cover.xhtml"),
                    Triple("details", "Description and Tags", "details.xhtml"),
                    Triple("toc", "Table of Contents", "toc.xhtml"),
                ).mapIndexed { index, item ->
                    "<navPoint id=\"navPoint-${item.first}\" playOrder=\"${index + 1}\"><navLabel><text>${xml(
                        item.second,
                    )}</text></navLabel><content src=\"${xml(item.third)}\"/></navPoint>"
                }.joinToString("")
            }
        val chapterStart = if (chaptersOnly) 1 else 4
        val chapterNav =
            chapters
                .mapIndexed { index, chapter ->
                    "<navPoint id=\"navPoint-${index + 1}\" playOrder=\"${index + chapterStart}\"><navLabel><text>${xml(
                        chapter.title,
                    )}</text></navLabel><content src=\"chapter_${index + 1}.xhtml\"/></navPoint>"
                }.joinToString("")

        return """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:webnovel:${xml(
            story.id,
        )}"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head><docTitle><text>${xml(
            story.title,
        )}</text></docTitle><navMap>$frontMatter$chapterNav</navMap></ncx>"""
    }

    fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
