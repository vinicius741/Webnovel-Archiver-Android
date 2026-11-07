package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticJournalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun recordsSurviveANewJournalInstanceLikeAProcessRestart() {
        val directory = folder.newFolder("diagnostics")
        DiagnosticJournal(directory, nowMillis = { 1_000L }, background = false).append(record(ts = 1_000L, n = 1))

        val restarted = DiagnosticJournal(directory, nowMillis = { 2_000L }, background = false)
        restarted.append(record(ts = 2_000L, n = 2))

        assertEquals(listOf(1, 2), read(restarted, since = 0L).map { it["n"].asInt })
    }

    @Test
    fun readsOnlyRecordsInsideTheWindow() {
        val journal = DiagnosticJournal(folder.newFolder("diagnostics"), background = false)
        journal.append(record(ts = 100L, n = 1))
        journal.append(record(ts = 500L, n = 2))

        assertEquals(listOf(2), read(journal, since = 400L).map { it["n"].asInt })
    }

    @Test
    fun backgroundWritesAreVisibleAfterFlush() {
        val journal = DiagnosticJournal(folder.newFolder("diagnostics"), background = true)
        repeat(50) { journal.append(record(ts = System.currentTimeMillis(), n = it)) }

        assertEquals(50, read(journal, since = 0L).size)
    }

    @Test
    fun tornAndMalformedLinesAreSkipped() {
        val directory = folder.newFolder("diagnostics")
        val journal = DiagnosticJournal(directory, background = false)
        journal.append(record(ts = System.currentTimeMillis(), n = 1))
        journal.flush()
        directory.listFiles()!!.single().appendText("{\"k\":\"log\",\"ts\":12\nnot json\n{\"no_ts\":true}\n")

        assertEquals(listOf(1), read(journal, since = 0L).map { it["n"].asInt })
    }

    @Test
    fun rotatesSegmentsAndPrunesToTheSizeCap() {
        val directory = folder.newFolder("diagnostics")
        val journal = DiagnosticJournal(directory, maxSegmentBytes = 1_000, maxTotalBytes = 3_000, background = false)
        repeat(200) { journal.append(record(ts = System.currentTimeMillis(), n = it, padding = "x".repeat(100))) }
        journal.flush()

        val segments = directory.listFiles()!!.toList()
        assertTrue("expected rotation", segments.size > 1)
        // The active segment is never deleted, so the cap may be exceeded by at most one segment.
        assertTrue(segments.sumOf(File::length) <= 3_000 + 1_200)
        val kept = read(journal, since = 0L).map { it["n"].asInt }
        assertEquals("newest records survive pruning", 199, kept.last())
        assertTrue("oldest records were pruned", kept.first() > 0)
    }

    @Test
    fun segmentsPastRetentionAreDeletedWhenANewSessionStarts() {
        val directory = folder.newFolder("diagnostics")
        DiagnosticJournal(directory, nowMillis = { 1_000L }, background = false).apply {
            append(record(ts = 1_000L, n = 1))
            flush()
        }
        directory.listFiles()!!.forEach { it.setLastModified(1_000L) }
        val now = 1_000L + DiagnosticJournal.RETENTION_MILLIS + 1
        val journal = DiagnosticJournal(directory, nowMillis = { now }, background = false)

        journal.append(record(ts = now, n = 2))

        assertEquals(listOf(2), read(journal, since = 0L).map { it["n"].asInt })
    }

    @Test
    fun appendBlockingWritesImmediately() {
        val journal = DiagnosticJournal(folder.newFolder("diagnostics"), background = true)

        journal.appendBlocking(record(ts = System.currentTimeMillis(), n = 7))

        assertEquals(listOf(7), read(journal, since = 0L).map { it["n"].asInt })
    }

    private fun record(
        ts: Long,
        n: Int,
        padding: String? = null,
    ): Map<String, Any?> = linkedMapOf("k" to "log", "ts" to ts, "n" to n, "pad" to padding)

    private fun read(
        journal: DiagnosticJournal,
        since: Long,
    ): List<JsonObject> = ArrayList<JsonObject>().also { list -> journal.forEachRecord(since) { list += it } }
}
