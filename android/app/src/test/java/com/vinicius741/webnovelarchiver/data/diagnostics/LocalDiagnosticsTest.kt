package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthIssue
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthKind
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticsTest {
    @After
    fun tearDown() {
        LocalDiagnostics.clear()
    }

    @Test
    fun ringIsBoundedAndNeverRetainsMessages() {
        repeat(LocalDiagnostics.MAX_EVENTS + 5) { index ->
            LocalDiagnostics.record(Log.WARN, IllegalArgumentException("secret-$index"), index.toLong())
        }

        val events = LocalDiagnostics.snapshot()

        assertEquals(LocalDiagnostics.MAX_EVENTS, events.size)
        assertEquals(5, events.first().timestampMillis)
        assertEquals("IllegalArgumentException", events.first().throwableType)
        assertFalse(events.joinToString().contains("secret"))
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
                    StorageHealthIssue(
                        document = "library index.json",
                        kind = StorageHealthKind.Corrupt,
                        detail = "private/path/to/story",
                    ),
                ),
            )

        val payload = DiagnosticExportPlanning.payload(appInfo(), health, queue, emptyList(), generatedAtMillis = 1)
        val rendered = payload.toString()

        assertEquals(mapOf("failed" to 1, "pending" to 1), payload.queue.byStatus)
        assertTrue(payload.queue.byErrorCategory.isEmpty())
        assertEquals("story_document.json", payload.storageIssues.single().document)
        assertFalse(rendered.contains("private-story"))
        assertFalse(rendered.contains("Private Title"))
        assertFalse(rendered.contains("session-secret"))
        assertFalse(rendered.contains("private/path"))
    }

    @Test
    fun infoLogsAreIgnored() {
        LocalDiagnostics.record(Log.INFO, null, 1)

        assertTrue(LocalDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun exportPlanBoundsStorageIssueMetadata() {
        val issues =
            (0..DiagnosticExportPlanning.MAX_STORAGE_ISSUES).map { index ->
                StorageHealthIssue("story-$index.json", StorageHealthKind.Corrupt, "private-$index")
            }

        val payload =
            DiagnosticExportPlanning.payload(
                appInfo(),
                StorageHealthSnapshot(issues),
                emptyList(),
                emptyList(),
                generatedAtMillis = 1,
            )

        assertEquals(DiagnosticExportPlanning.MAX_STORAGE_ISSUES, payload.storageIssues.size)
        assertTrue(payload.storageIssues.all { it.document == "story_document.json" })
    }

    private fun appInfo() = DiagnosticAppInfo("1", 1, "test", 36)
}
