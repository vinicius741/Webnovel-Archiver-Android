package com.vinicius741.webnovelarchiver.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun urlsKeepOnlySchemeAndHost() {
        assertEquals(
            "GET https://www.royalroad.com/… failed",
            DiagnosticRedactor.redact("GET https://www.royalroad.com/fiction/1/my-story?x=1#c failed"),
        )
        assertEquals("open content://…", DiagnosticRedactor.redact("open content://com.android.providers/document/12"))
        assertEquals("https://host.example/…", DiagnosticRedactor.redact("https://user:pw@host.example/a"))
    }

    @Test
    fun filePathsKeepOnlyTheLastSegment() {
        assertEquals(
            "DurableJson parse failed for …/library_index.json",
            DiagnosticRedactor.redact("DurableJson parse failed for /data/user/0/pkg/files/webnovel_archiver/library_index.json"),
        )
    }

    @Test
    fun secretsAreReplaced() {
        val redacted =
            DiagnosticRedactor.redact(
                "key=sk-or-v1-abcdef1234567890 Authorization: Bearer abc.def.ghi cookie=cf_clearance=xyz mail me@example.com",
            )!!

        assertFalse(redacted.contains("abcdef1234567890"))
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("cf_clearance=xyz"))
        assertFalse(redacted.contains("me@example.com"))
        assertTrue(redacted.contains("<email>"))
    }

    @Test
    fun ordinaryTextAndRatiosAreUntouched() {
        assertEquals("Downloaded 5/10 chapters and/or retried", DiagnosticRedactor.redact("Downloaded 5/10 chapters and/or retried"))
        assertEquals(
            "AI job work cancelled (logout): 3 queued dropped",
            DiagnosticRedactor.redact("AI job work cancelled (logout): 3 queued dropped"),
        )
    }

    @Test
    fun blankTextIsNullAndLongTextIsTruncated() {
        assertNull(DiagnosticRedactor.redact("  "))
        assertEquals(DiagnosticRedactor.MAX_TEXT_LENGTH + 1, DiagnosticRedactor.redact("a ".repeat(1_000))!!.length)
    }

    @Test
    fun throwablesAreStructuredWithBoundedFramesCausesAndAStableFingerprint() {
        val cause = IllegalArgumentException("bad /data/user/0/pkg/files/x/y.json")
        val error = IllegalStateException("outer", cause)

        val first = DiagnosticRedactor.throwable(error)
        val second = DiagnosticRedactor.throwable(error)
        val other = DiagnosticRedactor.throwable(RuntimeException("x"))

        assertEquals("java.lang.IllegalStateException", first["type"])
        assertTrue((first["frames"] as List<*>).size <= DiagnosticRedactor.MAX_FRAMES)
        val causeMap = first["cause"] as Map<*, *>
        assertEquals("java.lang.IllegalArgumentException", causeMap["type"])
        assertEquals("bad …/y.json", causeMap["msg"])
        assertFalse("only the root carries a fingerprint", causeMap.containsKey("fp"))
        assertEquals(first["fp"], second["fp"])
        assertNotEquals(first["fp"], other["fp"])
    }

    @Test
    fun causeChainsAreBounded() {
        var error: Throwable = RuntimeException("root")
        repeat(10) { error = RuntimeException("wrap $it", error) }

        var depth = 0
        var current: Map<*, *>? = DiagnosticRedactor.throwable(error)
        while (current != null) {
            depth += 1
            current = current["cause"] as? Map<*, *>
        }

        assertEquals(DiagnosticRedactor.MAX_CAUSE_DEPTH + 1, depth)
    }
}
