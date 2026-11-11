package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogFiles.LogFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActivityLogPlanningTest {
    @After
    fun tearDown() {
        ActivityLog.drainPendingForTest()
    }

    @Test
    fun eventMapPutsTheEnvelopeFirstAndDropsNullFields() {
        val map =
            ActivityLogPlanning.eventMap(
                seq = 7,
                timestampMillis = 1000,
                sessionId = "abc",
                category = ActivityCategory.SYNC,
                type = "sync_started",
                fields = listOf("storyId" to "rr_1", "title" to null, "chapters" to 3),
            )

        assertEquals(listOf("ts", "seq", "sid", "cat", "type", "storyId", "chapters"), map.keys.toList())
        assertEquals("sync", map["cat"])
    }

    @Test
    fun reservedFieldNamesAreRenamedSoTheEnvelopeIsNeverOverwritten() {
        val map = ActivityLogPlanning.eventMap(1, 2, "s", ActivityCategory.NET, "x", listOf("type" to "inner", "ts" to 5))

        assertEquals("x", map["type"])
        assertEquals("inner", map["f_type"])
        assertEquals(5, map["f_ts"])
    }

    @Test
    fun longStringsAndListsAreBounded() {
        val text = "a".repeat(ActivityLogPlanning.MAX_STRING_LENGTH + 50)
        val scrubbed = ActivityLogPlanning.scrubField("title", text) as String
        assertTrue(scrubbed.startsWith("a".repeat(ActivityLogPlanning.MAX_STRING_LENGTH)))
        assertTrue(scrubbed.endsWith("…(+50)"))

        val list = ActivityLogPlanning.scrubField("titles", (1..40).map { "t$it" }) as List<*>
        assertEquals(ActivityLogPlanning.MAX_COLLECTION_ITEMS + 1, list.size)
        assertEquals("…(+15 more)", list.last())
    }

    @Test
    fun stackFieldsGetALongerLimit() {
        val stack = "x".repeat(1000)

        assertEquals(stack, ActivityLogPlanning.scrubField("stack", stack))
    }

    @Test
    fun secretsAreRedactedByKeyAndByPattern() {
        assertEquals(ActivityLogPlanning.REDACTED, ActivityLogPlanning.scrubField("cookie", "session_id=abc"))
        assertEquals(ActivityLogPlanning.REDACTED, ActivityLogPlanning.scrubField("apiKey", "anything"))
        val message = ActivityLogPlanning.scrubField("message", "failed with Bearer abc.def and sk-or-v1-0123456789abcdef") as String
        assertFalse("abc.def" in message)
        assertFalse("sk-or-v1-0123456789abcdef" in message)
        val cookie = ActivityLogPlanning.scrubField("message", "header Cookie: session_id=secret123; other") as String
        assertFalse("secret123" in cookie)
    }

    @Test
    fun throwablesBecomeTheirTypeAndMessage() {
        assertEquals("IllegalStateException: boom", ActivityLogPlanning.scrubField("error", IllegalStateException("boom")))
    }

    @Test
    fun fileNamesRoundTripAndSortByDateThenPart() {
        val day = LocalDate.of(2026, 11, 11)
        assertEquals("activity_2026-11-11.ndjson", ActivityLogFiles.fileName(day, 0))
        assertEquals("activity_2026-11-11_2.ndjson", ActivityLogFiles.fileName(day, 2))
        assertEquals(ActivityLogFiles.LogFileName(day, 2), ActivityLogFiles.parseFileName("activity_2026-11-11_2.ndjson"))
        assertNull(ActivityLogFiles.parseFileName("last_version.txt"))
        assertNull(ActivityLogFiles.parseFileName("activity_garbage.ndjson"))

        val ordered =
            ActivityLogFiles.chronological(
                listOf(
                    LogFile("activity_2026-11-11_1.ndjson", 1),
                    LogFile("last_version.txt", 1),
                    LogFile("activity_2026-11-10.ndjson", 1),
                    LogFile("activity_2026-11-11.ndjson", 1),
                ),
            )
        assertEquals(
            listOf("activity_2026-11-10.ndjson", "activity_2026-11-11.ndjson", "activity_2026-11-11_1.ndjson"),
            ordered.map { it.name },
        )
    }

    @Test
    fun pruneDropsDaysOutsideTheWindowThenOldestFilesOverTheSizeCap() {
        val today = LocalDate.of(2026, 11, 11)
        val files =
            listOf(
                LogFile("activity_2026-11-04.ndjson", 10),
                LogFile("activity_2026-11-05.ndjson", 10),
                LogFile("activity_2026-11-09.ndjson", 40),
                LogFile("activity_2026-11-10.ndjson", 40),
                LogFile("activity_2026-11-11.ndjson", 40),
            )

        assertEquals(listOf("activity_2026-11-04.ndjson"), ActivityLogFiles.filesToPrune(files, today, maxTotalBytes = 1000))
        assertEquals(
            listOf("activity_2026-11-04.ndjson", "activity_2026-11-05.ndjson", "activity_2026-11-09.ndjson"),
            ActivityLogFiles.filesToPrune(files, today, maxTotalBytes = 80),
        )
    }

    @Test
    fun pruneAlwaysKeepsTheNewestFile() {
        val today = LocalDate.of(2026, 11, 11)

        assertEquals(
            emptyList<String>(),
            ActivityLogFiles.filesToPrune(listOf(LogFile("activity_2026-11-11.ndjson", 500)), today, maxTotalBytes = 10),
        )
    }

    @Test
    fun appendPartRollsOverOnlyWhenTheLatestPartIsFull() {
        val day = LocalDate.of(2026, 11, 11)
        assertEquals(0, ActivityLogFiles.appendPart(emptyList(), day))
        val files = listOf(LogFile("activity_2026-11-11.ndjson", 100), LogFile("activity_2026-11-11_1.ndjson", 5))
        assertEquals(1, ActivityLogFiles.appendPart(files, day, maxPartBytes = 10))
        assertEquals(2, ActivityLogFiles.appendPart(files.take(1) + LogFile("activity_2026-11-11_1.ndjson", 50), day, maxPartBytes = 10))
    }

    @Test
    fun recordBeforeInitBuffersValidJsonLinesAndNeverThrows() {
        ActivityLog.drainPendingForTest()
        ActivityLog.record(ActivityCategory.LIBRARY, "story_deleted", "storyId" to "rr_1", "title" to "Novel", "weird" to Any())

        val line = ActivityLog.drainPendingForTest().last()
        val json = Gson().fromJson(line, JsonObject::class.java)
        assertEquals("library", json["cat"].asString)
        assertEquals("story_deleted", json["type"].asString)
        assertEquals("rr_1", json["storyId"].asString)
        assertFalse(line.contains("\n"))
    }

    @Test
    fun routineSourceAccessNoiseIsNotMirroredButFailuresAndDecisionsAre() {
        assertFalse(ActivityLogPlanning.mirrorsSourceAccessEvent("net_request_start", mapOf("attemptId" to "a1")))
        assertFalse(ActivityLogPlanning.mirrorsSourceAccessEvent("net_request_finish", mapOf("ok" to true)))
        assertTrue(ActivityLogPlanning.mirrorsSourceAccessEvent("net_request_finish", mapOf("ok" to false, "code" to 403)))
        assertFalse(ActivityLogPlanning.mirrorsSourceAccessEvent("render_poll", mapOf("decision" to "keep")))
        assertTrue(ActivityLogPlanning.mirrorsSourceAccessEvent("render_poll", mapOf("decision" to "accept")))
        assertTrue(ActivityLogPlanning.mirrorsSourceAccessEvent("challenge_detected", emptyMap()))
    }
}
