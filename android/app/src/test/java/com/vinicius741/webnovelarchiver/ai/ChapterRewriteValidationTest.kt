package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Deterministic validation contract, ported from the Phase-1 spike's `selftest`: merge semantics,
 * protected-block mutation, the merge_slot_shift safety net, and output sanitization.
 */

class ChapterRewriteValidationTest {
    @Test
    fun `valid reply passes`() {
        val source = parse(GOOD_SOURCE)
        val result = ChapterRewriteValidation.validateRewrite(reply(REPLY_GOOD), source)
        assertTrue(result.issues.toString(), result.ok)
    }

    @Test
    fun `missing ids are rejected`() {
        val source = parse(GOOD_SOURCE)
        val bad = replyWithBlocks("""{"id": "b0001", "html": "<p>x</p>"}""")
        val result = ChapterRewriteValidation.validateRewrite(JsonParser.parseString(bad).asJsonObject, source)
        assertFalse(result.ok)
        assertEquals("ids", result.issues.first().code)
    }

    @Test
    fun `out of order ids are rejected`() {
        val source = parse("<p>Alpha sentence one.</p><p>Beta line here now.</p>")
        val swapped =
            replyWithBlocks(
                """{"id": "b0002", "html": "<p>Beta first.</p>"}""",
                """{"id": "b0001", "html": "<p>Alpha second.</p>"}""",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(swapped), source)
        assertFalse(result.ok)
        assertEquals("ids", result.issues.first().code)
        assertTrue(
            result.issues
                .first()
                .detail
                .contains("order"),
        )
    }

    @Test
    fun `protected mutation is rejected`() {
        val source = parse(GOOD_SOURCE)
        val mutated = REPLY_GOOD.replace("Level 4", "Level 5")
        val result = ChapterRewriteValidation.validateRewrite(reply(mutated), source)
        assertFalse(result.ok)
        assertEquals("protected_changed", result.issues.first().code)
    }

    @Test
    fun `protected block returned empty is rejected`() {
        val source = parse(GOOD_SOURCE)
        val emptied = REPLY_GOOD.replace("<blockquote><strong>[SYSTEM] Level 4</strong></blockquote>", "")
        val result = ChapterRewriteValidation.validateRewrite(reply(emptied), source)
        assertFalse(result.ok)
        assertEquals("protected_merged", result.issues.first().code)
    }

    @Test
    fun `output sanitizer strips script img and attributes from rewrites`() {
        val source = parse(GOOD_SOURCE)
        // Quotes are escaped for the JSON envelope; validation must still see and strip them.
        val scripted =
            REPLY_GOOD.replace(
                "<p>Alpha sentence one, unchanged.</p>",
                "<p onclick=\\\"x()\\\">Alpha <img src=\\\"http://x\\\">sentence one.</p>",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(scripted), source)
        assertTrue(result.issues.toString(), result.ok)
        assertFalse(result.blocks[0].html.contains("onclick"))
        assertFalse(result.blocks[0].html.contains("img"))
    }

    @Test
    fun `valid merge is accepted and counted`() {
        val source = parse("<p>Alpha sentence one.</p><p>Beta line here now.</p>")
        val merged =
            replyWithBlocks(
                """{"id": "b0001", "html": "<p>Alpha sentence one that absorbs Beta.</p>"}""",
                """{"id": "b0002", "html": ""}""",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(merged), source)
        assertTrue(result.ok)
        assertEquals(1, result.mergedCount)
        assertEquals("", result.blocks[1].html)
        assertEquals("merged", result.blocks[1].reason)
    }

    @Test
    fun `merge across a protected block is rejected`() {
        val source = parse("<p>First beat.</p><blockquote><strong>[PANEL]</strong></blockquote><p>Second beat.</p>")
        val merged =
            replyWithBlocks(
                """{"id": "b0001", "html": "<p>First beat.</p>"}""",
                """{"id": "b0002", "html": "<blockquote><strong>[PANEL]</strong></blockquote>"}""",
                """{"id": "b0003", "html": ""}""",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(merged), source)
        assertFalse(result.ok)
        assertEquals("merge_without_target", result.issues.first().code)
    }

    @Test
    fun `merge without a carrier is rejected`() {
        val source = parse("<p>One.</p><p>Two.</p>")
        val firstEmpty =
            replyWithBlocks(
                """{"id": "b0001", "html": ""}""",
                """{"id": "b0002", "html": "<p>x</p>"}""",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(firstEmpty), source)
        assertFalse(result.ok)
        assertEquals("merge_without_target", result.issues.first().code)
    }

    @Test
    fun `dense cluster merge of seven consecutive empties is accepted`() {
        val source = parse((1..8).joinToString("") { "<p>Beat $it.</p>" })
        val result = ChapterRewriteValidation.validateRewrite(reply(mergeAllReply(8)), source)
        assertTrue(result.issues.toString(), result.ok)
        assertEquals(7, result.mergedCount)
        assertEquals(7, result.maxEmptyRun)
    }

    @Test
    fun `pathological empty dump of twenty four is rejected as slot shift`() {
        val source = parse((1..24).joinToString("") { "<p>Beat $it.</p>" })
        val result = ChapterRewriteValidation.validateRewrite(reply(mergeAllReply(24)), source)
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "merge_slot_shift" })
    }

    @Test
    fun `unparseable reply is a validation failure not a crash`() {
        assertNull(ChapterRewriteValidation.parseModelReply("not json at all"))
        assertNotNull(ChapterRewriteValidation.parseModelReply("```json\n{\"blocks\": []}\n```"))
    }

    @Test
    fun `severe chapter shrink is rejected`() {
        val source = parse((1..10).joinToString("") { "<p>A fairly long sentence with many words number $it inside it.</p>" })
        val result =
            ChapterRewriteValidation.validateRewrite(
                reply(mergeAllReply(10, carrier = "<p>Everything compressed to almost nothing at all.</p>")),
                source,
            )
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "chapter_shrink" })
    }

    @Test
    fun `self audit flags become warnings not issues`() {
        val flagged =
            REPLY_GOOD
                .replace("\"protected_blocks_unchanged\": true", "\"protected_blocks_unchanged\": false")
                .replace("\"possible_drift\": []", "\"possible_drift\": [\"maybe lost a beat\"]")
        val source = parse(GOOD_SOURCE)
        val result = ChapterRewriteValidation.validateRewrite(reply(flagged), source)
        assertTrue(result.ok)
        assertTrue(result.warnings.any { it.code == "self_audit_flag" })
        assertTrue(result.warnings.any { it.code == "self_audit_drift" })
    }

    @Test
    fun `verifier verdict parses findings and treats unparseable as failure`() {
        val verdict =
            ChapterRewriteValidation.parseVerifierVerdict(
                """{"findings": [{"severity": "blocker", "type": "changed_number", "block_ids": ["b0003"], "evidence": "4 -> 5"}]}""",
            )
        assertEquals(1, verdict.findings.size)
        assertEquals(1, ChapterRewriteValidation.blockersOf(verdict).size)
        val unparseable = ChapterRewriteValidation.parseVerifierVerdict("garbage")
        assertNotNull(unparseable.parseError)
        assertTrue(unparseable.findings.isEmpty())
    }

    @Test
    fun `verifier object without a findings array is a failure never a clean pass`() {
        // A schema-ignoring reply (prose-ish JSON object, no findings array) must not read as
        // "verified, no blockers" — that would silently bypass the verification gate.
        val noFindings = ChapterRewriteValidation.parseVerifierVerdict("""{"result": "everything looks fine"}""")
        assertNotNull(noFindings.parseError)
        assertTrue(noFindings.findings.isEmpty())
        assertTrue(ChapterRewriteValidation.blockersOf(noFindings).isEmpty())

        val findingsNotArray = ChapterRewriteValidation.parseVerifierVerdict("""{"findings": "none"}""")
        assertNotNull(findingsNotArray.parseError)
        assertTrue(findingsNotArray.findings.isEmpty())

        // An explicitly empty findings array is the only shape that means "verified clean".
        val clean = ChapterRewriteValidation.parseVerifierVerdict("""{"findings": []}""")
        assertNull(clean.parseError)
    }

    @Test
    fun `non-object self audit degrades to the audit warning instead of crashing`() {
        // self_audit arriving as a non-object must fall into the existing warning branch —
        // never throw a ClassCastException out of validation.
        val malformed =
            REPLY_GOOD.replace(
                "\"self_audit\": {\"protected_blocks_unchanged\": true, \"possible_drift\": []}",
                "\"self_audit\": [\"not\", \"an\", \"object\"]",
            )
        val result = ChapterRewriteValidation.validateRewrite(reply(malformed), parse(GOOD_SOURCE))
        assertTrue(result.warnings.any { it.code == "self_audit_flag" })
    }

    private fun parse(html: String): ParsedChapter = ChapterBlockParsing.parseChapter(html)

    private fun reply(json: String) = JsonParser.parseString(json).asJsonObject

    /** Wraps block JSON fragments into a full reply with a clean self-audit. */
    private fun replyWithBlocks(vararg blocks: String): String =
        """{"blocks": [${blocks.joinToString(",")}], "self_audit": {"protected_blocks_unchanged": true, "possible_drift": []}}"""

    /** First block absorbs everything else: b0001 carries, b0002..bN return the empty string. */
    private fun mergeAllReply(
        count: Int,
        carrier: String = "<p>Beat one two three four five six seven eight.</p>",
    ): String {
        val blocks =
            (1..count).joinToString(", ") { index ->
                if (index == 1) {
                    """{"id": "b0001", "html": "$carrier"}"""
                } else {
                    """{"id": "b${"%04d".format(index)}", "html": ""}"""
                }
            }
        return """{"blocks": [$blocks], "self_audit": {"protected_blocks_unchanged": true, "possible_drift": []}}"""
    }

    private companion object {
        const val GOOD_SOURCE =
            "<p>Alpha sentence one.</p>" +
                "<blockquote><strong>[SYSTEM] Level 4</strong></blockquote>" +
                "<p>Beta line here now.</p>"

        val REPLY_GOOD =
            """
            {"blocks": [
              {"id": "b0001", "html": "<p>Alpha sentence one, unchanged.</p>"},
              {"id": "b0002", "html": "<blockquote><strong>[SYSTEM] Level 4</strong></blockquote>"},
              {"id": "b0003", "html": "<p>Beta line here now.</p>"}
            ], "self_audit": {"protected_blocks_unchanged": true, "possible_drift": []}}
            """.trimIndent()
    }
}
