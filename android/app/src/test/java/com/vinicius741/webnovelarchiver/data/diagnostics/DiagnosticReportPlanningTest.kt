package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportPlanningTest {
    private val gson = Gson()

    @Test
    fun reportIsOneSelfDescribingJsonDocumentWithEverySection() {
        val collector = DiagnosticReportPlanning.Collector()
        listOf(
            json(mapOf("k" to "session", "ts" to 1, "sid" to "s1")),
            json(LocalDiagnostics.logRecord("s1", android.util.Log.WARN, null, 2, "slow", "A.b:1")),
            json(mapOf("k" to "op", "ts" to 3, "sid" to "s1", "op" to "queue_save", "ms" to 40)),
            json(mapOf("k" to "src", "ts" to 4, "sid" to "s1", "cat" to "cf", "type" to "challenge_detected", "host" to "example.test")),
            json(crash(ts = 5)),
        ).forEach(collector::accept)

        val report = parse(document(collector, context = mapOf("app" to mapOf("versionName" to "1.0"))))

        assertEquals("wna-diagnostic-report", report["format"].asString)
        assertTrue(report.has("agentInstructions"))
        assertEquals(6L, report.getAsJsonObject("window")["hours"].asLong)
        assertEquals("1.0", report.getAsJsonObject("app")["versionName"].asString)
        val summary = report.getAsJsonObject("summary")
        assertEquals(1, summary["sessions"].asInt)
        assertEquals(1, summary["crashes"].asInt)
        assertEquals(1, summary["warnings"].asInt)
        assertEquals(1, report.getAsJsonArray("logs").size())
        assertEquals(1, report.getAsJsonArray("operations").size())
        assertEquals(1, report.getAsJsonArray("sourceEvents").size())
        assertEquals(1, report.getAsJsonObject("sourceSummary").getAsJsonObject("byType")["challenge_detected"].asInt)
        assertEquals(40L, report.getAsJsonArray("operationStats")[0].asJsonObject["maxMs"].asLong)
        assertFalse("journal kind is internal", report.getAsJsonArray("logs")[0].asJsonObject.has("k"))
    }

    @Test
    fun repeatedStackTracesAreStoredOnceAndReferencedByFingerprint() {
        val collector = DiagnosticReportPlanning.Collector()
        val error = IllegalStateException("same")
        repeat(5) { index ->
            collector.accept(json(LocalDiagnostics.logRecord("s1", android.util.Log.ERROR, error, index.toLong(), "failed", "A.b:1")))
        }

        val report = parse(document(collector))
        val logs = report.getAsJsonArray("logs").map { it.asJsonObject }
        val traces = report.getAsJsonObject("stackTraces")

        assertEquals(5, logs.size)
        val fingerprint = logs.first().getAsJsonObject("error")["fp"].asString
        assertTrue(logs.all { it.getAsJsonObject("error")["fp"].asString == fingerprint })
        assertFalse("inline errors carry no frames", logs.first().getAsJsonObject("error").has("frames"))
        assertEquals(1, traces.size())
        assertTrue(traces.getAsJsonObject(fingerprint).getAsJsonArray("frames").size() > 0)
        val topError = report.getAsJsonObject("summary").getAsJsonArray("topErrors")[0].asJsonObject
        assertEquals(5, topError["count"].asInt)
    }

    @Test
    fun oversizedReportsKeepCrashesAndNewestDetailWhileAggregatesCoverEverything() {
        val collector = DiagnosticReportPlanning.Collector()
        collector.accept(json(crash(ts = 0)))
        repeat(3_000) { index ->
            collector.accept(
                json(
                    mapOf(
                        "k" to "src",
                        "ts" to index + 1,
                        "sid" to "s1",
                        "seq" to index,
                        "type" to "net_request_start",
                        "pad" to "x".repeat(200),
                    ),
                ),
            )
        }

        val maxBytes = 64 * 1024
        val text = document(collector, maxBytes = maxBytes)
        val report = parse(text)
        val sources = report.getAsJsonArray("sourceEvents").map { it.asJsonObject }

        assertTrue(text.toByteArray().size <= maxBytes)
        assertEquals(1, report.getAsJsonArray("crashes").size())
        assertTrue(sources.size in 1 until 3_000)
        assertEquals("newest event survives", 2_999, sources.last()["seq"].asInt)
        assertEquals(3_000, report.getAsJsonObject("summary")["sourceEvents"].asInt)
        assertEquals(sources.size, report.getAsJsonObject("summary").getAsJsonObject("included")["sourceEvents"].asInt)
        assertEquals(3_000, report.getAsJsonObject("sourceSummary").getAsJsonObject("byType")["net_request_start"].asInt)
    }

    @Test
    fun collectorListsAreBoundedToTheNewestRecords() {
        val collector = DiagnosticReportPlanning.Collector()
        repeat(DiagnosticReportPlanning.MAX_OPERATIONS + 10) { index ->
            collector.accept(json(mapOf("k" to "op", "ts" to index, "op" to "x", "ms" to 1)))
        }

        val kept = collector.operations.toList()
        assertEquals(DiagnosticReportPlanning.MAX_OPERATIONS, kept.size)
        assertEquals(10L, kept.first()["ts"].asLong)
        assertEquals(DiagnosticReportPlanning.MAX_OPERATIONS + 10, collector.operations.seen)
    }

    private fun crash(ts: Long): Map<String, Any?> =
        linkedMapOf(
            "k" to "crash",
            "ts" to ts,
            "sid" to "s1",
            "thread" to "main",
            "error" to DiagnosticRedactor.throwable(RuntimeException("boom")),
        )

    private fun document(
        collector: DiagnosticReportPlanning.Collector,
        context: Map<String, Any?> = emptyMap(),
        maxBytes: Int = DiagnosticReportPlanning.MAX_REPORT_BYTES,
    ): String =
        DiagnosticReportPlanning.document(
            window = DiagnosticWindow.LAST_6_HOURS,
            generatedAtMillis = 10_000L,
            context = context,
            exitReasons = emptyList(),
            collector = collector,
            serialize = gson::toJson,
            maxBytes = maxBytes,
        )

    private fun json(map: Map<String, Any?>): JsonObject = JsonParser.parseString(gson.toJson(map)).asJsonObject

    private fun parse(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
}
