package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthIssue
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthKind
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var journal: DiagnosticJournal

    @Before
    fun installJournal() {
        journal = DiagnosticJournal(folder.newFolder("diagnostics"), background = false)
        DiagnosticJournal.active = journal
    }

    @After
    fun tearDown() {
        DiagnosticJournal.active = null
    }

    @Test
    fun warningsKeepARedactedMessageOriginAndStructuredStackTrace() {
        LocalDiagnostics.record(
            Log.ERROR,
            IllegalStateException("Failed https://www.royalroad.com/fiction/123/secret-story?token=abc"),
            timestampMillis = 5,
            message = "Could not save /data/user/0/app/files/webnovel_archiver/stories/abc/story.json",
            origin = "AppRepository.save:42",
        )

        val record = records().single()
        val error = record.getAsJsonObject("error")

        assertEquals("log", record["k"].asString)
        assertEquals("E", record["level"].asString)
        assertEquals("AppRepository.save:42", record["origin"].asString)
        assertEquals("Could not save …/story.json", record["msg"].asString)
        assertEquals("java.lang.IllegalStateException", error["type"].asString)
        assertEquals("Failed https://www.royalroad.com/…", error["msg"].asString)
        assertTrue(error.getAsJsonArray("frames").size() > 0)
        assertTrue(error.has("fp"))
        assertFalse(record.toString().contains("secret-story"))
    }

    @Test
    fun infoLogsAreIgnored() {
        LocalDiagnostics.record(Log.INFO, null, 1)

        assertTrue(records().isEmpty())
    }

    @Test
    fun operationsAndCrashesAreJournaled() {
        LocalDiagnostics.recordOperation("queue save!", 12, failed = true)
        LocalDiagnostics.recordCrash(Thread.currentThread(), RuntimeException("boom"))

        val (operation, crash) = records()

        assertEquals("op", operation["k"].asString)
        assertEquals("queue_save_", operation["op"].asString)
        assertEquals(12L, operation["ms"].asLong)
        assertTrue(operation["failed"].asBoolean)
        assertEquals("crash", crash["k"].asString)
        assertEquals("java.lang.RuntimeException", crash.getAsJsonObject("error")["type"].asString)
    }

    @Test
    fun successfulOperationsOmitTheFailedFlag() {
        LocalDiagnostics.recordOperation("reader_prepare", 3)

        assertFalse(records().single().has("failed"))
    }

    @Test
    fun recordingWithoutAJournalIsANoOp() {
        DiagnosticJournal.active = null

        LocalDiagnostics.record(Log.ERROR, RuntimeException("ignored"))
        LocalDiagnostics.recordOperation("x", 1)
    }

    @Test
    fun exportPlanContainsOnlyAggregateQueueAndTypedStorageMetadata() {
        val queue =
            listOf(
                DownloadJob(storyId = "private-story", storyTitle = "Private Title", status = "pending"),
                DownloadJob(
                    storyId = "https://secret.example/story",
                    storyTitle = "Another Private Title",
                    status = "failed",
                    error = "cookie=session-secret",
                    errorCategory = "network/url",
                ),
            )
        val health =
            StorageHealthSnapshot(
                listOf(
                    StorageHealthIssue(document = "library index.json", kind = StorageHealthKind.Corrupt, detail = "private/path/to/story"),
                ),
            )

        val queueSummary = DiagnosticExportPlanning.queueSummary(queue)
        val issues = DiagnosticExportPlanning.storageIssues(health)
        val rendered = "$queueSummary $issues"

        assertEquals(mapOf("failed" to 1, "pending" to 1), queueSummary.byStatus)
        assertTrue(queueSummary.byErrorCategory.isEmpty())
        assertEquals("story_document.json", issues.single().document)
        assertFalse(rendered.contains("private-story"))
        assertFalse(rendered.contains("Private Title"))
        assertFalse(rendered.contains("session-secret"))
        assertFalse(rendered.contains("private/path"))
    }

    @Test
    fun storageIssuesAreBounded() {
        val issues =
            (0..DiagnosticExportPlanning.MAX_STORAGE_ISSUES).map { index ->
                StorageHealthIssue("story-$index.json", StorageHealthKind.Corrupt, "private-$index")
            }

        val summary = DiagnosticExportPlanning.storageIssues(StorageHealthSnapshot(issues))

        assertEquals(DiagnosticExportPlanning.MAX_STORAGE_ISSUES, summary.size)
        assertTrue(summary.all { it.document == "story_document.json" })
    }

    @Test
    fun librarySummaryCountsWithoutTitlesOrIds() {
        val stories =
            listOf(
                Story(id = "secret-id", title = "Secret Title", sourceId = "royalroad", totalChapters = 10, downloadedChapters = 4),
                Story(id = "other", title = "Other", sourceId = null, totalChapters = 2, downloadedChapters = 2),
            )

        val summary = DiagnosticExportPlanning.librarySummary(stories)

        assertEquals(2, summary.stories)
        assertEquals(12, summary.chapters)
        assertEquals(6, summary.downloadedChapters)
        assertEquals(mapOf("royalroad" to 1, "unresolved" to 1), summary.bySource)
        assertFalse(summary.toString().contains("Secret"))
        assertFalse(summary.toString().contains("secret-id"))
    }

    private fun records(): List<JsonObject> = ArrayList<JsonObject>().also { list -> journal.forEachRecord(0L) { list += it } }
}
