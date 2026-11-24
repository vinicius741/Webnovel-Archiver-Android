package com.vinicius741.webnovelarchiver.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOcrTest {
    @get:Rule val temp = TemporaryFolder()

    /** Lines 30 px tall on a 40 px pitch; [rows] are (text, left) and a null row is a blank line's worth of space. */
    private fun page(vararg rows: Pair<String, Int>?): List<OcrLine> =
        rows.mapIndexedNotNull { index, row ->
            row?.let { (text, left) -> OcrLine(text, left, index * 40, left + 800, index * 40 + 30) }
        }

    private val prose = "The lantern keeper climbed the hill an hour before dusk, as she had every evening."

    @Test
    fun scansAreMostlyPagesWithoutText() {
        assertTrue(PdfOcrPlanning.isScan(listOf("", " ", prose)))
        assertFalse(PdfOcrPlanning.isScan(listOf(prose, "", prose)))
        assertFalse(PdfOcrPlanning.isScan(emptyList()))
    }

    @Test
    fun aScannersStampOrPageNumberIsNotText() {
        val stamped = listOf("Scanned with CamScanner\n1", "Scanned with CamScanner\n2", prose)

        assertTrue(PdfOcrPlanning.isScan(stamped))
        assertEquals(setOf(0, 1), PdfOcrPlanning.pagesToRecognize(stamped, 3))
    }

    @Test
    fun recognizesThePagesWithoutTextOfAScanAndEveryPageWithoutATextLayer() {
        assertEquals(setOf(0, 2, 3), PdfOcrPlanning.pagesToRecognize(listOf("", prose, "", ""), 4))
        assertEquals(emptySet<Int>(), PdfOcrPlanning.pagesToRecognize(listOf(prose, "", prose), 3))
        assertEquals(setOf(0, 1, 2), PdfOcrPlanning.pagesToRecognize(null, 3))
    }

    @Test
    fun anIndentedLineStartsAParagraph() {
        val text =
            OcrPageLayout.text(
                page(
                    "The Book" to 300,
                    "12" to 450,
                    "of the night was long and" to 100,
                    "quiet." to 100,
                    "She waited by the door" to 140,
                    "until the bell rang." to 100,
                    "He came in at last and" to 140,
                    "sat down." to 100,
                    "The Book" to 300,
                ),
            )

        assertEquals(
            "The Book\n12\nof the night was long and\nquiet.\n\nShe waited by the door\nuntil the bell rang.\n\nHe came in at last and\nsat down.\nThe Book",
            text,
        )
    }

    @Test
    fun extraSpaceAboveALineStartsAParagraph() {
        val text =
            OcrPageLayout.text(
                page(
                    "One" to 100,
                    "two" to 100,
                    "three" to 100,
                    "four" to 100,
                    null,
                    "* * *" to 100,
                    "five" to 100,
                    "six" to 100,
                ),
            )

        assertEquals("One\ntwo\nthree\nfour\n\n* * *\nfive\nsix", text)
    }

    @Test
    fun theEdgesOfAPageNeverBreakAParagraph() {
        // A centered header and a page number with space around them: removed later by PdfTextReflow,
        // so a blank line next to them would end the sentence that runs on from the previous page.
        val text =
            OcrPageLayout.text(
                page("Chapter Title" to 300, null, "ran on into the" to 100, "night and" to 100, "then" to 100, null, "37" to 450),
            )

        assertEquals("Chapter Title\nran on into the\nnight and\nthen\n37", text)
    }

    @Test
    fun theNextColumnIsNotAnIndent() {
        val left = (0 until 6).map { OcrLine("left $it", 100, it * 40, 700, it * 40 + 30) }
        val right = (0 until 6).map { OcrLine("right $it", 800, it * 40, 1400, it * 40 + 30) }

        assertEquals((left + right).joinToString("\n") { it.text }, OcrPageLayout.text(left + right))
    }

    @Test
    fun anIndentedBlockQuoteIsNotAParagraphPerLine() {
        val text =
            OcrPageLayout.text(
                page(
                    "One" to 100,
                    "two" to 100,
                    "three" to 100,
                    "Dear Ann, I write" to 160,
                    "to say the boat" to 160,
                    "is lost and so" to 160,
                    "are we." to 160,
                    "four" to 100,
                    "five" to 100,
                    "six" to 100,
                ),
            )

        // The quote starts a paragraph; its other lines do not.
        assertEquals("One\ntwo\nthree\n\nDear Ann, I write\nto say the boat\nis lost and so\nare we.\nfour\nfive\nsix", text)
    }

    @Test
    fun aLineOfDialogueAsLongAsTheLineIsStillAParagraph() {
        val text =
            OcrPageLayout.text(
                page(
                    "One" to 100,
                    "two" to 100,
                    "dust." to 100,
                    "\u201CAnother night,\u201D she said to nobody." to 140,
                    "The stairs wound upward in a" to 140,
                    "tight spiral." to 100,
                    "five" to 100,
                    "six" to 100,
                ),
            )

        assertEquals(
            "One\ntwo\ndust.\n\n\u201CAnother night,\u201D she said to nobody.\n\nThe stairs wound upward in a\ntight spiral.\nfive\nsix",
            text,
        )
    }

    @Test
    fun aLineOfDialogueIsAParagraphOfItsOwn() {
        fun line(
            text: String,
            row: Int,
            left: Int,
            right: Int = 900,
        ) = OcrLine(text, left, row * 40, right, row * 40 + 30)

        val text =
            OcrPageLayout.text(
                listOf(
                    line("One", 0, 100),
                    line("two", 1, 100),
                    line("of oil and dust.", 2, 100, right = 500),
                    line("\u201CAnother night,\u201D she said.", 3, 140, right = 600),
                    line("The stairs wound upward in a", 4, 140),
                    line("tight spiral and she climbed.", 5, 100),
                    line("five", 6, 100),
                    line("six", 7, 100),
                ),
            )

        assertEquals(
            "One\ntwo\nof oil and dust.\n\n\u201CAnother night,\u201D she said.\n\nThe stairs wound upward in a\n" +
                "tight spiral and she climbed.\nfive\nsix",
            text,
        )
    }

    @Test
    fun aDoubleSpacedPageIsNotAParagraphPerLine() {
        val lines = (0 until 8).map { OcrLine("line $it", 100, it * 80, 900, it * 80 + 30) }

        assertFalse(OcrPageLayout.text(lines).contains("\n\n"))
    }

    @Test
    fun piecesOfOneLineAreJoined() {
        val text =
            OcrPageLayout.text(
                page("One" to 100, "two" to 100, "three" to 100, null, "five" to 100, "six" to 100) +
                    listOf(OcrLine("She walked into", 100, 120, 450, 150), OcrLine("the dark room.", 470, 122, 850, 152)),
            )

        assertEquals("One\ntwo\nthree\nShe walked into the dark room.\nfive\nsix", text)
    }

    @Test
    fun aLineWithoutABoxDoesNotFailThePage() {
        val lines = page("One" to 100, "two" to 100, "three" to 100, "four" to 100, "five" to 100, "six" to 100, "seven" to 100)

        val text = OcrPageLayout.text(lines + List(4) { OcrLine("boxless $it", 0, 0, 0, 0) })

        assertTrue(text.contains("boxless 3") && text.contains("seven"))
    }

    @Test
    fun linesComeInReadingOrderWhateverOrderTheyAreRecognizedIn() {
        val header = OcrLine("The Book", 300, 0, 600, 30)
        val carried = OcrLine("missing.", 100, 80, 300, 110)
        val next = OcrLine("She counted again.", 140, 120, 900, 150)
        val sideBySide = OcrLine("again", 500, 160, 700, 188)
        val sideBySideLeft = OcrLine("Once", 100, 162, 400, 190)

        assertEquals(
            listOf(header, carried, next, sideBySideLeft, sideBySide),
            OcrPageLayout.readingOrder(listOf(carried, next, sideBySide, header, sideBySideLeft)),
        )
    }

    @Test
    fun aTwoPageSpreadReadsTheLeftPageFirst() {
        val left = (0 until 3).map { OcrLine("left $it", 100, it * 40, 700, it * 40 + 30) }
        val right = (0 until 3).map { OcrLine("right $it", 800, it * 40, 1400, it * 40 + 30) }
        val interleaved = left.zip(right).flatMap { (l, r) -> listOf(r, l) }

        assertEquals(left + right, OcrPageLayout.readingOrder(interleaved))
    }

    @Test
    fun aSkewedScansLinesStayInOrder() {
        // A tilted line's box is taller than the line, so it overlaps the next line's box.
        val first = OcrLine("first", 100, 100, 1300, 198)
        val second = OcrLine("second", 90, 148, 1290, 246)
        val third = OcrLine("third", 80, 196, 1280, 294)

        assertEquals(listOf(first, second, third), OcrPageLayout.readingOrder(listOf(first, second, third)))
    }

    @Test
    fun aCenteredHeadingStaysAboveShortLinesBesideIt() {
        val heading = OcrLine("Chapter 5", 450, 0, 650, 40)
        val dialogue = (1..4).map { OcrLine("\u201CNo.\u201D $it", 100, it * 50, 230, it * 50 + 30) }

        assertEquals(listOf(heading) + dialogue, OcrPageLayout.readingOrder(dialogue.reversed() + heading))
    }

    @Test
    fun aTitleAcrossTwoColumnsComesFirstAndTheColumnsStayApart() {
        val title = OcrLine("The Lantern Keeper", 500, 0, 1000, 40)
        val left = (1..4).map { OcrLine("left $it", 100, it * 50, 700, it * 50 + 30) }
        val right = (1..4).map { OcrLine("right $it", 800, it * 50, 1400, it * 50 + 30) }

        assertEquals(listOf(title) + left + right, OcrPageLayout.readingOrder(left.zip(right).flatMap { (l, r) -> listOf(r, l) } + title))
    }

    @Test
    fun savedPagesAreKeyedByContent() {
        val cache = PdfOcrCache(temp.newFolder("ocr"))
        val book = temp.newFile("a.pdf").apply { writeText("scan") }
        val renamed = temp.newFile("b.pdf").apply { writeText("scan") }
        val edited = temp.newFile("c.pdf").apply { writeText("scan, edited") }

        cache.book(book).save(0, "Page one.")

        assertEquals("Page one.", cache.book(renamed).page(0))
        assertNull(cache.book(book).page(1))
        assertNull(cache.book(edited).page(0))
    }
}
