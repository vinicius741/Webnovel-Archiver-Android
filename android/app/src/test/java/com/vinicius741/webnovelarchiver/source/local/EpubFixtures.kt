package com.vinicius741.webnovelarchiver.source.local

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A small EPUB 3: a cover page, a title page, chapters 1–2 sharing one file (split at an anchor), and chapter 3 over two files. */
internal object EpubFixtures {
    val entries: Map<String, String> =
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to
                """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>The Small Book</dc:title><dc:creator>A. Writer</dc:creator>
                    <dc:description>&lt;p&gt;A tale.&lt;/p&gt;</dc:description><dc:subject>Fantasy</dc:subject>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="cover-img" href="images/cover.png" media-type="image/png" properties="cover-image"/>
                    <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="title" href="text/title.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c12" href="text/ch%2012.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3a" href="text/ch3a.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3b" href="text/ch3b.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="cover"/><itemref idref="title"/><itemref idref="c12"/><itemref idref="c3a"/><itemref idref="c3b"/></spine>
                </package>""",
            "OEBPS/nav.xhtml" to
                """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                <li><a href="text/ch%2012.xhtml">Chapter 1</a></li>
                <li><a href="text/ch%2012.xhtml#two">Chapter 2</a></li>
                <li><a href="text/ch3a.xhtml">Chapter 3</a></li>
                </ol></nav></body></html>""",
            "OEBPS/text/cover.xhtml" to "<html><body><img src=\"../images/cover.png\"/></body></html>",
            "OEBPS/text/title.xhtml" to "<html><body><h1>The Small Book</h1><p>by A. Writer</p></body></html>",
            "OEBPS/text/ch 12.xhtml" to
                """<html><head><title>x</title><script>alert(1)</script></head><body><div class="wrap">
                <h2>Chapter 1</h2><p>One <a href="#n">begins</a>.</p><p></p>
                <section id="two"><h2>Chapter 2</h2><p>Two goes on.</p></section></div></body></html>""",
            "OEBPS/text/ch3a.xhtml" to "<html><body><h2>Chapter 3</h2><p>Three, first half.</p></body></html>",
            "OEBPS/text/ch3b.xhtml" to "<html><body><p>Three, second half.</p><img src=\"x.png\"/></body></html>",
            "OEBPS/images/cover.png" to "PNG",
        )

    fun read(path: String): ByteArray? = entries[path]?.toByteArray()

    fun zip(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
}
