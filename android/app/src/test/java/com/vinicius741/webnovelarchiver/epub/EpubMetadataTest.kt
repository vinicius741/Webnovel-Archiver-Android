package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubMetadataTest {
    @Test
    fun opfIncludesExpectedMetadataFieldsAndGuide() {
        val story =
            Story(
                id = "story&1",
                title = "Title <One>",
                author = "Author \"Name\"",
                description = "Description & details",
                tags = mutableListOf("Fantasy", "Sci-Fi"),
            )
        val chapters =
            listOf(
                Chapter(id = "c1", title = "Chapter 1"),
                Chapter(id = "c2", title = "Chapter 2"),
            )

        val opf =
            EpubMetadata.opf(
                story,
                chapters,
                EpubCoverMetadata("images/cover.png", "image/png"),
            )

        assertTrue(opf.contains("<dc:title>Title &lt;One&gt;</dc:title>"))
        assertTrue(opf.contains("<dc:creator opf:role=\"aut\">Author &quot;Name&quot;</dc:creator>"))
        assertTrue(opf.contains("<dc:identifier id=\"BookId\">urn:webnovel:story&amp;1</dc:identifier>"))
        assertTrue(opf.contains("<dc:description>Description &amp; details</dc:description>"))
        assertTrue(opf.contains("<dc:subject>Fantasy</dc:subject>"))
        assertTrue(opf.contains("<dc:subject>Sci-Fi</dc:subject>"))
        assertTrue(opf.contains("<meta name=\"cover\" content=\"cover-image\" />"))
        assertTrue(opf.contains("<item id=\"cover-image\" href=\"images/cover.png\" media-type=\"image/png\"/>"))
        assertTrue(opf.contains("<guide>"))
        assertTrue(opf.contains("<reference type=\"cover\" title=\"Cover\" href=\"cover.xhtml\"/>"))
        assertTrue(opf.contains("<reference type=\"toc\" title=\"Table of Contents\" href=\"toc.xhtml\"/>"))
        assertTrue(
            opf.contains("<itemref idref=\"cover\"/><itemref idref=\"details\"/><itemref idref=\"toc\"/><itemref idref=\"chapter_1\"/>"),
        )
    }

    @Test
    fun ncxIncludesDoctypeDtbMetadataAndFrontMatter() {
        val ncx =
            EpubMetadata.ncx(
                Story(id = "story", title = "Story"),
                listOf(Chapter(id = "c1", title = "Chapter & One")),
            )

        assertTrue(ncx.contains("<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\""))
        assertTrue(ncx.contains("<meta name=\"dtb:uid\" content=\"urn:webnovel:story\"/>"))
        assertTrue(ncx.contains("<meta name=\"dtb:depth\" content=\"1\"/>"))
        assertTrue(ncx.contains("<meta name=\"dtb:totalPageCount\" content=\"0\"/>"))
        assertTrue(ncx.contains("<meta name=\"dtb:maxPageNumber\" content=\"0\"/>"))
        assertTrue(ncx.contains("<text>Description and Tags</text>"))
        assertTrue(ncx.contains("<text>Chapter &amp; One</text>"))
        assertTrue(ncx.contains("playOrder=\"4\""))
    }

    @Test
    fun opfOmitsAllFrontMatterWhenChaptersOnly() {
        val story = Story(id = "s1", title = "Title", author = "Author")
        val chapters = listOf(Chapter(id = "c1", title = "Chapter 1"))

        val opf =
            EpubMetadata.opf(
                story,
                chapters,
                cover = null,
                chaptersOnly = true,
            )

        // "Chapters only" drops every front-matter page: cover, details, TOC, and the cover image.
        assertFalse(opf.contains("href=\"cover.xhtml\""))
        assertFalse(opf.contains("href=\"details.xhtml\""))
        assertFalse(opf.contains("href=\"toc.xhtml\""))
        assertFalse(opf.contains("id=\"cover-image\""))
        assertFalse(opf.contains("<itemref idref=\"cover\"/>"))
        assertFalse(opf.contains("<itemref idref=\"details\"/>"))
        assertFalse(opf.contains("<itemref idref=\"toc\"/>"))
        assertFalse(opf.contains("<guide>"))
        // Chapters are always present, and the spine starts directly at chapter_1.
        assertTrue(opf.contains("<item id=\"chapter_1\""))
        assertTrue(opf.contains("<spine toc=\"ncx\"><itemref idref=\"chapter_1\"/>"))
    }

    @Test
    fun ncxStartsChaptersAtPlayOrderOneWhenChaptersOnly() {
        val story = Story(id = "s1", title = "Title")
        val chapters = listOf(Chapter(id = "c1", title = "Chapter 1"))

        val ncx =
            EpubMetadata.ncx(
                story,
                chapters,
                chaptersOnly = true,
            )

        // No front-matter navPoints remain, so the first chapter is playOrder 1 (not the historical 4).
        assertFalse(ncx.contains("navPoint-cover"))
        assertFalse(ncx.contains("navPoint-details"))
        assertFalse(ncx.contains("navPoint-toc"))
        assertFalse(ncx.contains("Description and Tags"))
        assertTrue(ncx.contains("<navPoint id=\"navPoint-1\" playOrder=\"1\">"))
    }
}
