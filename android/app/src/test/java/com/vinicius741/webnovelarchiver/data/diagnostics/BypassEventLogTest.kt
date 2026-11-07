package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilitySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BypassEventLogTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        DiagnosticJournal.active = null
    }

    @Test
    fun recordedEventsPersistAsSourceRecordsWithSession() {
        val journal = DiagnosticJournal(folder.newFolder("diagnostics"), background = false)
        DiagnosticJournal.active = journal

        BypassEventLog.record(BypassEventCategory.NET, "net_request_start", "Example.Test", "n" to 1)

        val records = ArrayList<JsonObject>().also { list -> journal.forEachRecord(0L) { list += it } }
        val record = records.single()
        assertEquals("src", record["k"].asString)
        assertEquals(journal.sessionId, record["sid"].asString)
        assertEquals("net", record["cat"].asString)
        assertEquals("example.test", record["host"].asString)
        assertEquals(1, record["n"].asInt)
    }

    @Test
    fun recordingWithoutAJournalIsANoOp() {
        BypassEventLog.record(BypassEventCategory.NET, "net_request_start", "example.test")
    }

    @Test
    fun sequenceIsMonotonic() {
        val first = BypassEventLog.build(BypassEventCategory.NET, "a")
        val second = BypassEventLog.build(BypassEventCategory.NET, "b")

        assertEquals(first.seq + 1, second.seq)
    }

    @Test
    fun nullFieldValuesAndNullHostAreOmitted() {
        val event = BypassEventLog.build(BypassEventCategory.CF, "render_finished", null, "code" to null, "ok" to true)

        assertEquals(null, event.host)
        assertFalse(event.fields.containsKey("code"))
        assertEquals(true, event.fields["ok"])
        assertFalse(event.toJsonMap().containsKey("host"))
    }

    @Test
    fun sanitizationKeepsUrlsAndPathsOutOfEveryField() {
        val event =
            BypassEventLog.build(
                BypassEventCategory.NET,
                "net_request_start",
                "https://evil.example/path?q=1",
                "path" to "https://www.scribblehub.com/read/12345/chapter/67890",
                "title" to "Some Story Title!!",
            )

        val json = Gson().toJson(event.toJsonMap())

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
    @Test
    fun documentIsJsonLinesWithASelfDescribingHeaderFirst() {
        val header =
            BypassLogExportPlanning.header(
                appInfo = DiagnosticAppInfo("1.0.0", 1, "debug", 36),
                reliability = listOf(snapshot()),
                queueSummary = DiagnosticExportPlanning.queueSummary(listOf(job())),
                window = DiagnosticWindow.LAST_6_HOURS,
                truncatedCount = 0,
                eventCount = 1,
                generatedAtMillis = 1_000L,
            )
        val event = sourceRecord(BypassEventLog.build(BypassEventCategory.CF, "render_poll", "example.test", "pollN" to 3))

        val (lines, extraDropped) = BypassLogExportPlanning.documentLines(header, listOf(event), Gson()::toJson)

        assertEquals(0, extraDropped)
        assertEquals(2, lines.size)
        val gson = Gson()
        val headerObject = gson.fromJson(lines[0], Map::class.java)
        val eventObject = gson.fromJson(lines[1], Map::class.java)
        assertEquals("wna-source-access-log", headerObject["format"])
        assertEquals(6.0, (headerObject["window"] as Map<*, *>)["hours"])
        assertTrue(headerObject.containsKey("agentInstructions"))
        assertEquals("cf", eventObject["cat"])
        assertFalse("the journal kind is internal", eventObject.containsKey("k"))
        assertEquals(3.0, eventObject["pollN"])
    }

    @Test
    fun oversizedDocumentsDropOldestEventsFirst() {
        val header = minimalHeader()
        val events =
            (1..500).map { index ->
                sourceRecord(
                    BypassEvent(
                        seq = index.toLong(),
                        timestampMillis = index.toLong(),
                        category = BypassEventCategory.NET,
                        type = "net_request_start",
                        host = "example.test",
                        fields = mapOf("padding" to "x".repeat(500)),
                    ),
                )
            }

        val (lines, extraDropped) = BypassLogExportPlanning.documentLines(header, events, Gson()::toJson)

        assertTrue("expected events to be dropped", extraDropped > 0)
        assertTrue(lines.joinToString("\n").toByteArray().size <= BypassLogExportPlanning.MAX_EXPORT_BYTES)
        val gson = Gson()
        assertEquals("net_request_start", gson.fromJson(lines[1], Map::class.java)["type"])
        // Trimming drops a prefix, so the newest event always survives as the last line.
        assertEquals(events.last()["seq"].asDouble, gson.fromJson(lines.last(), Map::class.java)["seq"])
        assertEquals(events[extraDropped]["seq"].asDouble, gson.fromJson(lines[1], Map::class.java)["seq"])
    }

    private fun minimalHeader(): Map<String, Any?> =
        BypassLogExportPlanning.header(
            appInfo = DiagnosticAppInfo("1.0.0", 1, "debug", 36),
            reliability = emptyList(),
            queueSummary = DiagnosticQueueSummary(0, emptyMap(), emptyMap()),
            window = DiagnosticWindow.LAST_HOUR,
            truncatedCount = 0,
            eventCount = 0,
            generatedAtMillis = 0L,
        )

    private fun sourceRecord(event: BypassEvent): JsonObject =
        JsonParser.parseString(Gson().toJson(mapOf("k" to "src") + event.toJsonMap())).asJsonObject

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
