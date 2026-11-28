package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.cleanup.DefaultCleanup
import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.epub.EpubContent
import com.vinicius741.webnovelarchiver.feature.reader.ChapterHtmlSanitizer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.Deflater

class RoyalRoadParagraphClassesTest {
    // Real class shapes: Base64 of "rs" + 31 hex digits for paragraphs, of "r" + 32 hex for the notice marker.
    private val paragraph = "cnMxODhjNDkwZjVkNzRmOWVhODhjNDZkOTQ4OWQzZGI0"
    private val marker = "cjgyODA2MTgxYTY5NTRhYzc4M2Q1NTAyYmZjNWI1Nzc0"
    private val rules = listOf(RegexCleanupRule(id = "r", name = "brackets", pattern = "\\[Edited\\]", appliesTo = "both"))

    @Test
    fun dropsParagraphClassesAndKeepsOtherAttributes() {
        val html =
            """<p class="$paragraph">One.</p>""" +
                """<p class="$paragraph" style="margin-bottom: 0" align="left"><span style="font-weight: 400">Two.</span></p>"""

        assertEquals(
            """<p>One.</p><p style="margin-bottom: 0" align="left"><span style="font-weight: 400">Two.</span></p>""",
            RoyalRoadParagraphClasses.compact(html),
        )
    }

    @Test
    fun keepsNoticeMarkerAndEveryOtherClass() {
        val html =
            """<p class="text-center">A</p><p class="$marker">B</p><p class="$paragraph extra">C</p>""" +
                """<span class="$paragraph">D</span><span class="$marker"></span><div class="$paragraph">E</div>"""

        assertSame(html, RoyalRoadParagraphClasses.compact(html))
    }

    @Test
    fun leavesARunOnAttributeAlone() {
        // Without a space before the next attribute, dropping the class would fuse `<p` and `style`.
        val html = """<p class="$paragraph"style="margin: 0">Text.</p>"""

        assertSame(html, RoyalRoadParagraphClasses.compact(html))
    }

    @Test
    fun returnsTheSameInstanceWhenNothingIsLeftToDrop() {
        val once = RoyalRoadParagraphClasses.compact("""<p class="$paragraph">Story.</p>""")

        assertSame(once, RoyalRoadParagraphClasses.compact(once))
    }

    @Test
    fun downloadDropsParagraphClassesButTheNoticeMarkerStillMarksTheChapter() {
        val page =
            """<html><body><div class="chapter-inner chapter-content">""" +
                """<p class="$paragraph">Before.</p><span class="$marker">Stolen story; please report.</span>""" +
                """<p class="$paragraph">After.</p></div></body></html>"""

        val saved = RoyalRoadProvider.parseChapterContent(page)

        assertFalse(saved.contains(paragraph))
        assertTrue(saved.contains("""<span class="$marker"></span>"""))
        // DownloadEngine's notice-marker monitor and later cleanups run on this saved text.
        assertNotNull(RoyalRoadNoticeMarker.strip(saved))
    }

    @Test
    fun royalRoadIsTheOnlySourceWithACompactor() {
        val withCompactor = SourceRegistry.all().filter { it.chapterMarkupCompactor != null }.map { it.id }

        assertEquals(listOf("royal_road"), withCompactor)
    }

    @Test
    fun storedChapterReadsSpeaksAndExportsTheSame() {
        val html = requireNotNull(javaClass.getResource("/fixtures/royalroad/chapter_stored.html")).readText()

        val compacted = RoyalRoadParagraphClasses.compact(html)

        assertTrue(compacted.length < html.length * 0.9)
        assertRendersTheSame(html, compacted)
    }

    /**
     * Opt-in check over real chapters: `WNA_RR_SAMPLES=<dir of pulled .html files>`. Prints the
     * size saving; skipped when the variable is unset, so no chapter text lives in the repository.
     */
    @Test
    fun realChaptersRenderTheSame() {
        val dir = System.getenv("WNA_RR_SAMPLES")?.let(::File)?.takeIf(File::isDirectory) ?: return
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
        var before = 0L
        var after = 0L
        var zippedBefore = 0L
        var zippedAfter = 0L
        var compactNanos = 0L
        files.forEach { file ->
            val html = file.readText()
            val started = System.nanoTime()
            val compacted = RoyalRoadParagraphClasses.compact(html)
            compactNanos += System.nanoTime() - started
            before += html.toByteArray().size
            after += compacted.toByteArray().size
            zippedBefore += deflatedSize(html.toByteArray())
            zippedAfter += deflatedSize(compacted.toByteArray())
            assertRendersTheSame(html, compacted, file.name)
        }
        println(
            "RR samples: files=${files.size} bytesBefore=$before bytesAfter=$after " +
                "saved=${"%.1f".format(100.0 * (before - after) / before)}% compactMs=${compactNanos / 1_000_000} " +
                "deflate6Before=$zippedBefore deflate6After=$zippedAfter",
        )
    }

    /** [html] reserialized without paragraph-noise class tokens and with sorted attributes. */
    private fun withoutNoiseClasses(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        body.getAllElements().forEach { element ->
            val attributes = element.attributes().asList().map { it.key to it.value }
            attributes.forEach { (key, _) -> element.removeAttr(key) }
            attributes.sortedBy { it.first }.forEach { (key, value) ->
                val kept = if (key == "class") value.split(' ').filterNot { it.isBlank() || isNoise(it) }.sorted() else listOf(value)
                if (kept.isNotEmpty()) element.attr(key, kept.joinToString(" "))
            }
        }
        return body.html()
    }

    private fun isNoise(token: String) = RoyalRoadParagraphClasses.compact("<p class=\"$token\">") == "<p>"

    /** Per-file deflate at level 6, what the full-backup ZIP stores. */
    private fun deflatedSize(bytes: ByteArray): Long {
        val deflater =
            Deflater(6).apply {
                setInput(bytes)
                finish()
            }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (!deflater.finished()) total += deflater.deflate(buffer)
        deflater.end()
        return total
    }

    private fun assertRendersTheSame(
        html: String,
        compacted: String,
        label: String = "fixture",
    ) {
        fun compact(text: String) = RoyalRoadParagraphClasses.compact(text)
        // TTS: the spoken chunks and their breaks, which also feed the narration fingerprint.
        assertEquals(label, TtsTextPreparation.prepareTtsNarration(html, rules), TtsTextPreparation.prepareTtsNarration(compacted, rules))
        // Reader: the annotated WebView body and plain text. The reader merges its `tts-chunk` class
        // into a paragraph's own, so the bodies are compared as DOMs without the noise classes.
        val readerBefore = TtsTextPreparation.prepareTtsAnnotatedHtml(ChapterHtmlSanitizer.sanitize(html), rules)
        val readerAfter = TtsTextPreparation.prepareTtsAnnotatedHtml(ChapterHtmlSanitizer.sanitize(compacted), rules)
        assertEquals(label, readerBefore.chunks, readerAfter.chunks)
        assertEquals(label, withoutNoiseClasses(readerBefore.annotatedHtml), withoutNoiseClasses(readerAfter.annotatedHtml))
        assertEquals(label, HtmlCleanup.htmlToFormattedText(html), HtmlCleanup.htmlToFormattedText(compacted))
        // EPUB: the chapter body differs only by the dropped classes.
        assertEquals(label, compact(EpubContent.sanitizeContent(html)), EpubContent.sanitizeContent(compacted))
        // Cleanup and the notice marker: the same removals, and a marked chapter stays marked.
        val markerBefore = RoyalRoadNoticeMarker.strip(html)
        val markerAfter = RoyalRoadNoticeMarker.strip(compacted)
        if (markerBefore == null) {
            assertNull(label, markerAfter)
        } else {
            assertEquals(label, markerBefore.noticesRemoved, markerAfter?.noticesRemoved)
        }
        val cleanBefore = CleanupEngine.shared.applyDownloadWithStats(html, DefaultCleanup.sentences, rules, RoyalRoadNoticeMarker)
        val cleanAfter = CleanupEngine.shared.applyDownloadWithStats(compacted, DefaultCleanup.sentences, rules, RoyalRoadNoticeMarker)
        assertEquals(label, cleanBefore.copy(html = compact(cleanBefore.html)), cleanAfter)
    }
}
