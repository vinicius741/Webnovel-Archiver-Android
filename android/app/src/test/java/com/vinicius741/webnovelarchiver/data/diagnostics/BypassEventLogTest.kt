package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilitySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassEventLogTest {
    @After fun tearDown() {
        BypassEventLog.clear()
    }

    @Test
    fun ringDropsOldestEventsAndCountsThem() {
        repeat(BypassEventLog.MAX_EVENTS + 25) { index ->
            BypassEventLog.record(BypassEventCategory.NET, "net_request_start", "example.test", "n" to index)
        }

        val snapshot = BypassEventLog.snapshot()

        assertEquals(BypassEventLog.MAX_EVENTS, snapshot.size)
        assertEquals(25L, BypassEventLog.droppedCount())
        assertEquals(25, snapshot.first().fields["n"])
        assertEquals(BypassEventLog.MAX_EVENTS + 24, snapshot.last().fields["n"])
    }

    @Test
    fun nullFieldValuesAndNullHostAreOmitted() {
        BypassEventLog.record(BypassEventCategory.CF, "render_finished", null, "code" to null, "ok" to true)

        val event = BypassEventLog.snapshot().single()

        assertEquals(null, event.host)
        assertFalse(event.fields.containsKey("code"))
        assertEquals(true, event.fields["ok"])
        assertFalse(event.toJsonMap().containsKey("host"))
    }

    @Test
    fun sanitizationKeepsUrlsAndPathsOutOfEveryField() {
        BypassEventLog.record(
            BypassEventCategory.NET,
            "net_request_start",
            "https://evil.example/path?q=1",
            "path" to "https://www.scribblehub.com/read/12345/chapter/67890",
            "title" to "Some Story Title!!",
        )

        val json = Gson().toJson(BypassEventLog.snapshot().single().toJsonMap())

        assertFalse("scheme separators must not survive", json.contains("://"))
        assertFalse("path separators must not survive", json.contains("/"))
        assertFalse("query separators must not survive", json.contains("?"))
        assertFalse("raw titles must not survive", json.contains("Some Story Title"))
    }

    @Test
    fun correlationIdsAreUniqueAndPrefixed() {
        val first = BypassEventLog.nextId("a")
        val second = BypassEventLog.nextId("a")
        val render = BypassEventLog.nextId("r")

        assertTrue(first.startsWith("a"))
        assertTrue(render.startsWith("r"))
        assertEquals(first.removePrefix("a").toLong() + 1, second.removePrefix("a").toLong())
    }
}

class BypassLogExportPlanningTest {
    @After fun tearDown() {
        BypassEventLog.clear()
    }

    @Test
    fun documentIsJsonLinesWithASelfDescribingHeaderFirst() {
        val header =
            BypassLogExportPlanning.header(
                appInfo = DiagnosticAppInfo("1.0.0", 1, "debug", 36),
                reliability = listOf(snapshot()),
                queueSummary = DiagnosticExportPlanning.queueSummary(listOf(job())),
                droppedCount = 0,
                eventCount = 1,
                generatedAtMillis = 1_000L,
            )
        BypassEventLog.record(BypassEventCategory.CF, "render_poll", "example.test", "pollN" to 3)

        val (lines, extraDropped) = BypassLogExportPlanning.documentLines(header, BypassEventLog.snapshot(), Gson()::toJson)

        assertEquals(0, extraDropped)
        assertEquals(2, lines.size)
        val gson = Gson()
        val headerObject = gson.fromJson(lines[0], Map::class.java)
        val eventObject = gson.fromJson(lines[1], Map::class.java)
        assertEquals("wna-source-access-log", headerObject["format"])
        assertTrue(headerObject.containsKey("agentInstructions"))
        assertEquals("cf", eventObject["cat"])
        assertEquals(3.0, eventObject["pollN"])
    }

    @Test
    fun oversizedDocumentsDropOldestEventsFirst() {
        val header = minimalHeader()
        val events =
            (1..500).map { index ->
                BypassEvent(
                    seq = index.toLong(),
                    timestampMillis = index.toLong(),
                    category = BypassEventCategory.NET,
                    type = "net_request_start",
                    host = "example.test",
                    fields = mapOf("padding" to "x".repeat(500)),
                )
            }

        val (lines, extraDropped) = BypassLogExportPlanning.documentLines(header, events, Gson()::toJson)

        assertTrue("expected events to be dropped", extraDropped > 0)
        assertTrue(lines.joinToString("\n").toByteArray().size <= BypassLogExportPlanning.MAX_EXPORT_BYTES)
        val gson = Gson()
        assertEquals("net_request_start", gson.fromJson(lines[1], Map::class.java)["type"])
        // Trimming drops a prefix, so the newest event always survives as the last line.
        assertEquals(events.last().seq.toDouble(), gson.fromJson(lines.last(), Map::class.java)["seq"])
        assertEquals(events[extraDropped].seq.toDouble(), gson.fromJson(lines[1], Map::class.java)["seq"])
    }

    private fun minimalHeader(): Map<String, Any?> =
        BypassLogExportPlanning.header(
            appInfo = DiagnosticAppInfo("1.0.0", 1, "debug", 36),
            reliability = emptyList(),
            queueSummary = DiagnosticQueueSummary(0, emptyMap(), emptyMap()),
            droppedCount = 0,
            eventCount = 0,
            generatedAtMillis = 0L,
        )

    private fun snapshot() =
        SourceReliabilitySnapshot(
            host = "example.test",
            browserTransportActive = false,
            manualVerificationRequired = false,
            cooldownRemainingMillis = 0L,
            effectiveMinimumGapMillis = 0L,
            requestCount = 1L,
            challengeCount = 0L,
            rateLimitCount = 0L,
            browserRenderCount = 0L,
        )

    private fun job() = DownloadJob(id = "job", status = "pending", chapter = Chapter(url = "example.test"))
}
