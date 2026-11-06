package com.vinicius741.webnovelarchiver.cleanup

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SentenceMatchingTest {
    private fun remove(
        text: String,
        vararg sentences: String,
    ) = SentenceMatching.remove(text, sentences.map(SentenceMatching::prepare))

    @Test
    fun removesEveryOccurrenceIgnoringCase() {
        val removal = remove("Stolen story. Keep. STOLEN STORY. stolen story.", "Stolen story.")

        assertEquals(" Keep.  ", removal?.text)
        assertEquals(3, removal?.removed)
    }

    @Test
    fun foldsNonAsciiCase() {
        assertEquals(" Fim.", remove("ÉCOUTEZ ÇA. Fim.", "écoutez ça.")?.text)
    }

    @Test
    fun appliesEntriesInListOrderToTheRemainingText() {
        val removal = remove("abcabc", "b", "ac")

        assertEquals("", removal?.text)
        assertEquals(4, removal?.removed)
    }

    @Test
    fun collapsesWhitespaceInsideEntries() {
        assertEquals(" Story.", remove("Report it. Story.", "  Report \n it. ")?.text)
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        assertNull(remove("Story.", "Absent."))
        assertNull(remove("Story.", "   "))
    }

    @Test
    fun candidatesFindEntriesThroughEscapedAndUppercaseHtml() {
        val list = SentenceList(listOf("Tom & Jerry's show.", "Stolen story; please report.", "Absent words here."))
        val html = Jsoup.parseBodyFragment("<p>TOM &amp; JERRY&#39;S SHOW.</p><p>stolen <b>x</b> STORY; PLEASE REPORT.</p>").body().html()

        assertEquals(listOf("tom & jerry's show.", "stolen story; please report."), list.candidatesIn(html))
    }

    @Test
    fun entriesWithoutAnAsciiRunAreAlwaysCandidates() {
        val list = SentenceList(listOf("———", "«»", "Report it."))

        assertEquals(listOf("———", "«»"), list.candidatesIn("<p>Nothing here.</p>"))
    }

    @Test
    fun oneAndTwoCharacterAnchorsAreFound() {
        val list = SentenceList(listOf("~", "**", "#"))

        assertEquals(listOf("~", "**"), list.candidatesIn("<p>a ~ b ** c</p>"))
    }

    @Test
    fun candidatesNeverMissAnEntryThatOccursInTheText() {
        val random = Random(741)
        val alphabet = "aAbB -—'&<>\"é.*~\u00a0"
        repeat(5_000) {
            val text = List(random.nextInt(1, 16)) { alphabet.random(random) }.joinToString("")
            val html =
                Jsoup
                    .parseBodyFragment("<p></p>")
                    .body()
                    .also { it.child(0).text(text) }
                    .html()
            val nodeText =
                Jsoup
                    .parseBodyFragment(html)
                    .selectFirst("p")!!
                    .textNodes()
                    .joinToString("") { it.text() }
            val entries = List(random.nextInt(1, 4)) { nodeText.drop(random.nextInt(nodeText.length + 1)).take(random.nextInt(1, 6)) }
            val list = SentenceList(entries)
            val candidates = list.candidatesIn(html)
            list.entries.filter { SentenceMatching.fold(nodeText).contains(it) }.forEach { entry ->
                assertTrue("'$entry' occurs in '$nodeText' but is not a candidate of $html", entry in candidates)
            }
        }
    }

    @Test
    fun foldsSupplementaryLettersAsWholeCodePoints() {
        assertEquals(" x", remove("\uD801\uDC28 x", "\uD801\uDC00")?.text)
    }

    @Test
    fun duplicateEntriesKeepTheirPlaceInTheList() {
        val result = CleanupEngine().applyDownloadWithStats("<p>aXb</p>", listOf("ab", "X", "AB"), emptyList())

        assertEquals(2, result.sentencesRemoved)
        assertEquals("", Jsoup.parseBodyFragment(result.html).body().text())
    }

    @Test
    fun candidatesSeeThroughCharactersJsoupDrops() {
        val list = SentenceList(listOf("Report it."))

        assertEquals(list.entries, list.candidatesIn("<p>Re\u200Bp\u00ADort it.</p>"))
    }

    @Test
    fun anEntityJsoupNeverWritesMakesEveryEntryACandidate() {
        val list = SentenceList(listOf("Report it.", "Absent."))

        assertEquals(list.entries, list.candidatesIn("<p>Re&#112;ort it.</p>"))
        assertEquals(listOf<String>(), list.candidatesIn("<p>Tom &amp; Jerry&nbsp;&lt;3</p>"))
    }

    @Test
    fun jsoupOnlyWritesEntitiesTheScanAllows() {
        // If a Jsoup upgrade starts escaping more, every chapter would silently fall back to the
        // slow all-candidates path; this makes that visible.
        val html =
            Jsoup
                .parseBodyFragment("<p title='a\"b&c<d'>x</p>")
                .body()
                .also { body ->
                    body.child(0).text("& < > \" ' \u00a0 é — ’ \u200B \uD801\uDC00 ~ \\ ` |")
                }.html()

        assertEquals(listOf<String>(), SentenceList(listOf("absent")).candidatesIn(html))
    }

    /** Half a supplementary character, which a typed list entry can never hold. */
    private fun splitsASurrogatePair(entry: String): Boolean =
        entry.indices.any { index ->
            val char = entry[index]
            (Character.isHighSurrogate(char) && !(index + 1 < entry.length && Character.isLowSurrogate(entry[index + 1]))) ||
                (Character.isLowSurrogate(char) && !(index > 0 && Character.isHighSurrogate(entry[index - 1])))
        }

    /** The sentence pass before this matcher: one escaped, case-insensitive regex per entry. */
    private fun regexCleanup(
        html: String,
        sentences: List<String>,
    ): Pair<String, Int> {
        val patterns = sentences.filter { it.isNotBlank() }.map { Regex(Regex.escape(it.trim()), RegexOption.IGNORE_CASE) }
        val doc = Jsoup.parseBodyFragment(html)
        var removed = 0
        doc.body().select("*").flatMap { it.textNodes() }.forEach { node ->
            var text = node.text()
            patterns.forEach { pattern ->
                removed += pattern.findAll(text).count()
                text = pattern.replace(text, "")
            }
            node.text(text)
        }
        return doc.body().text() to removed
    }

    @Test
    fun removesWhatTheRegexPassRemovedWhateverTheHtmlEncoding() {
        val random = Random(1741)
        val units = listOf("a", "A", "b", "B", "p", "P", " ", "-", ".", "é", "É", "\u200B", "\u00AD", "&", "\uD801\uDC00", "\uD801\uDC28")
        val unmarked = CleanupEngine()
        val marked = CleanupEngine()
        val keepAsIs = InsertedNoticeMarker { InsertedNoticeStrip(it, 0) }
        repeat(3_000) {
            val text = List(random.nextInt(1, 14)) { units.random(random) }
            val html =
                "<p>" +
                    text.joinToString("") { unit ->
                        when {
                            random.nextInt(4) == 0 -> "&#${unit.codePointAt(0)};"
                            unit == "&" -> "&amp;"
                            else -> unit
                        }
                    } + "</p>"
            val nodeText = Jsoup.parseBodyFragment(html).body().text()
            val entries =
                List(random.nextInt(1, 4)) {
                    val from = random.nextInt(nodeText.length + 1)
                    nodeText.substring(from, (from + random.nextInt(1, 6)).coerceAtMost(nodeText.length))
                }.filter { entry -> entry.isNotBlank() && !entry.trim().contains("  ") && !splitsASurrogatePair(entry) }
            val expected = regexCleanup(html, entries)
            listOf(
                unmarked.applyDownloadWithStats(html, entries, emptyList()),
                marked.applyDownloadWithStats(html, entries, emptyList(), keepAsIs),
            ).forEach { result ->
                val message = "entries=$entries html=$html"
                assertEquals(message, expected.first, Jsoup.parseBodyFragment(result.html).body().text())
                assertEquals(message, expected.second, result.sentencesRemoved)
            }
        }
    }
}
