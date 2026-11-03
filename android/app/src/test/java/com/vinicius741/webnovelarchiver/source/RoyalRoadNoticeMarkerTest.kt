package com.vinicius741.webnovelarchiver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalRoadNoticeMarkerTest {
    // Real class shapes: Base64 of "r" + 32 hex digits, and of "rs" + 31 hex digits for paragraphs.
    private val marker = "cjgyODA2MTgxYTY5NTRhYzc4M2Q1NTAyYmZjNWI1Nzc0"
    private val paragraph = "cnMxODhjNDkwZjVkNzRmOWVhODhjNDZkOTQ4OWQzZGI0"

    private fun notice(content: String) = """<span class="$marker">$content</span>"""

    @Test
    fun emptiesMarkedNoticeAndKeepsSurroundingStory() {
        val html =
            """<p class="$paragraph">Before.</p>""" +
                notice(" \n <br> \n <br> \n Stolen story; please report.\n") +
                """<p class="$paragraph">After.</p>"""

        val result = RoyalRoadNoticeMarker.strip(html)!!

        assertEquals(1, result.noticesRemoved)
        assertEquals("""<p class="$paragraph">Before.</p>${notice("")}<p class="$paragraph">After.</p>""", result.html)
    }

    @Test
    fun leavesBlankMarkerUntouchedSoCleanFilesAreNotRewritten() {
        val html = """<p>Story.</p>${notice(" \n <br> \n <br> \n")}<p>More.</p>"""

        val result = RoyalRoadNoticeMarker.strip(html)!!

        assertEquals(0, result.noticesRemoved)
        assertSame(html, result.html)
    }

    @Test
    fun strippedChapterIsStillRecognizedAsMarked() {
        val once = RoyalRoadNoticeMarker.strip(notice("Stolen novel; please report."))!!

        val twice = RoyalRoadNoticeMarker.strip(once.html)!!

        assertEquals(0, twice.noticesRemoved)
        assertEquals(once.html, twice.html)
    }

    @Test
    fun removesLeftoverPunctuationFromEarlierSentenceRemoval() {
        assertEquals(1, RoyalRoadNoticeMarker.strip(notice(" <br> <br> . "))!!.noticesRemoved)
    }

    @Test
    fun removesNestedSpansWithTheNotice() {
        val result = RoyalRoadNoticeMarker.strip("${notice("<span>Stolen</span> story")}<span>Kept</span>")!!

        assertEquals("${notice("")}<span>Kept</span>", result.html)
    }

    @Test
    fun paragraphClassOnSpanIsNotAMarker() {
        assertNull(RoyalRoadNoticeMarker.strip("""<span class="$paragraph">Story text.</span>"""))
    }

    @Test
    fun otherClassesAndAttributesAreNotMarkers() {
        assertNull(RoyalRoadNoticeMarker.strip("<p>Plain chapter.</p>"))
        assertNull(RoyalRoadNoticeMarker.strip("""<span class="note">Author note.</span>"""))
        assertNull(RoyalRoadNoticeMarker.strip("""<span style="color: red" class="$marker">Styled text.</span>"""))
        assertNull(RoyalRoadNoticeMarker.strip("""<p class="$marker">Paragraph.</p>"""))
    }

    @Test
    fun unclosedMarkerIsNotTrusted() {
        assertNull(RoyalRoadNoticeMarker.strip("""<span class="$marker">Everything after this."""))
    }

    @Test
    fun parseChapterContentRemovesInjectedNotice() {
        val html =
            """
            <html><body><div class="chapter-inner">
              <p>Keep this paragraph.</p>
              <span class="$marker"><br><br>You could be reading stolen content.</span>
              <p>Keep this one too.</p>
            </div></body></html>
            """.trimIndent()

        val content = RoyalRoadProvider.parseChapterContent(html)

        assertFalse(content.contains("stolen content"))
        assertTrue(content.contains("Keep this paragraph."))
        assertTrue(content.contains("Keep this one too."))
        assertEquals(0, RoyalRoadProvider.insertedNoticeMarker.strip(content)!!.noticesRemoved)
    }
}
