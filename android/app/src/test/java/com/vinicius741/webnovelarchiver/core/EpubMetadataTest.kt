package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertTrue
import org.junit.Test

class EpubMetadataTest {
    @Test
    fun opfIncludesReactNativeMetadataFieldsAndGuide() {
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
}
