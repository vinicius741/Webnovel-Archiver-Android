package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogExportPlanningTest {
    private val gson = Gson()

    @Test
    fun documentKeepsEveryEventWhenItFits() {
        val events = listOf("{\"seq\":1}", "{\"seq\":2}")

        val lines = ActivityLogExportPlanning.documentLines(events, maxBytes = 10_000) { count, dropped -> "{\"n\":$count,\"d\":$dropped}" }

        assertEquals(listOf("{\"n\":2,\"d\":0}") + events, lines)
    }

    @Test
    fun documentDropsTheOldestEventsToFitAndSaysSoInTheHeader() {
        val events = (1..10).map { "{\"seq\":$it,\"pad\":\"${"x".repeat(20)}\"}" }

        val lines = ActivityLogExportPlanning.documentLines(events, maxBytes = 200) { count, dropped -> "{\"n\":$count,\"d\":$dropped}" }

        val joined = lines.joinToString("\n") + "\n"
        assertTrue(joined.toByteArray().size <= 200)
        val header = gson.fromJson(lines.first(), JsonObject::class.java)
        val kept = header["n"].asInt
        assertEquals(10 - kept, header["d"].asInt)
        assertEquals(events.takeLast(kept), lines.drop(1))
    }

    @Test
    fun headerSummarizesTheLibraryPerNovel() {
        val story =
            Story(
                id = "rr_1",
                title = "Novel",
                sourceId = "royal_road",
                chapters =
                    mutableListOf(
                        Chapter(id = "1", downloaded = true),
                        Chapter(id = "2", downloaded = true, removedFromSourceAt = 5L),
                        Chapter(id = "patreon_3", downloaded = true, patreonPostId = "3"),
                        Chapter(id = "4"),
                    ),
            )

        val header =
            ActivityLogExportPlanning.header(
                generatedAtMillis = 1,
                app = mapOf("versionName" to "1.0"),
                library = listOf(story),
                queue = DiagnosticQueueSummary(0, emptyMap(), emptyMap()),
                reliability = emptyList(),
                logFiles = emptyList(),
                eventCount = 0,
                droppedForSize = 0,
                droppedBeforeWrite = 0,
                writeFailures = 0,
            )
        val json = gson.fromJson(gson.toJson(header), JsonObject::class.java)

        assertEquals(ActivityLogExportPlanning.FORMAT, json["format"].asString)
        val summary = json.getAsJsonArray("library")[0].asJsonObject
        assertEquals(4, summary["chapters"].asInt)
        assertEquals(3, summary["downloaded"].asInt)
        assertEquals(1, summary["removedFlagged"].asInt)
        assertEquals(1, summary["patreonCopies"].asInt)
        assertEquals(1, json.getAsJsonObject("libraryStats")["removedFlagged"].asInt)
        assertTrue(json["agentInstructions"].asString.isNotBlank())
    }

    @Test
    fun onlyTheNewestExportsAreKept() {
        val names =
            listOf(
                "webnovel_activity_log_100.json",
                "webnovel_activity_log_300.json",
                "webnovel_backup_1.json",
                "webnovel_activity_log_200.json",
                "webnovel_activity_log_400.json",
            )

        assertEquals(listOf("webnovel_activity_log_100.json"), ActivityLogExportPlanning.exportsToPrune(names))
    }
}
