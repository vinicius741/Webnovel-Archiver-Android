package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteDraftOutput
import com.vinicius741.webnovelarchiver.ai.ChapterBlock
import com.vinicius741.webnovelarchiver.ai.ChapterCadenceReport
import com.vinicius741.webnovelarchiver.ai.ChapterRewriteValidation.VerifierFinding
import com.vinicius741.webnovelarchiver.ai.ChapterRewriteValidation.VerifierVerdict
import com.vinicius741.webnovelarchiver.ai.RewriteIssue
import com.vinicius741.webnovelarchiver.ai.RewriteValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The repository layer's draft-output → persisted-record mapping (ChapterRewriteDraftMapping).
 * The AppRepository transaction wrappers themselves ride the same storage monitor as every other
 * mutation and are exercised end-to-end on the emulator; the mapping is the pure part worth
 * pinning here. (AppStorage needs an Android Context, so a JVM AppRepositoryRewritesTest cannot
 * construct the real storage owner.)
 */

class ChapterRewriteDraftMappingTest {
    @Test
    fun `verified draft output maps to a ready record with cost and provenance`() {
        val output = output(status = "ready", verdict = VerifierVerdict(emptyList()))

        val record = output.toDraftRecord()

        assertEquals("ready", record.status)
        assertEquals("verified", record.verification.status)
        assertEquals(0, record.verification.blockerCount)
        assertEquals("openai/gpt-5.6-terra", record.model)
        assertEquals("x-ai/grok-4.6", record.verifierModel)
        assertEquals("v1.2-light", record.promptVersion)
        assertEquals("0.158", record.costUsd)
        assertEquals(31, record.mergedBlocks)
        assertEquals("strict", record.providerTier)
        assertEquals("sha-source", record.sourceSha256)
        assertTrue(record.validationWarnings.contains("severe_shrink: b0004: 40->5 words"))
    }

    @Test
    fun `blocked verdict maps to blockers count and findings`() {
        val verdict =
            VerifierVerdict(
                listOf(
                    VerifierFinding("blocker", "changed_number", listOf("b0002"), "4 vs 5"),
                    VerifierFinding("warning", "intention_drift", listOf("b0009"), "asks a different question"),
                ),
            )
        val record = output(status = "blocked", verdict = verdict).toDraftRecord()

        assertEquals("blocked", record.status)
        assertEquals("blocked", record.verification.status)
        assertEquals(1, record.verification.blockerCount)
        assertEquals(2, record.verification.findings.size)
        assertEquals(
            "changed_number",
            record.verification.findings
                .first()
                .type,
        )
    }

    @Test
    fun `unparseable verifier reply maps to verify_failed which can never apply`() {
        val record = output(status = "verify_failed", verdict = VerifierVerdict(emptyList(), parseError = "unparseable")).toDraftRecord()

        assertEquals("verify_failed", record.status)
        assertEquals("verify_failed", record.verification.status)
    }

    @Test
    fun `cadence comparison summary carries the template swap warning through`() {
        val record = output(status = "ready", verdict = VerifierVerdict(emptyList())).toDraftRecord()

        assertEquals(0.875, record.cadence.fragmentShareBefore, 1e-9)
        assertEquals(0.0, record.cadence.fragmentShareAfter, 1e-9)
        assertEquals(2, record.cadence.clusterCountBefore)
        assertEquals(0, record.cadence.clusterCountAfter)
        assertTrue(record.cadence.templateSwapWarning)
        assertTrue(record.cadence.templateSwapDetail.contains("swapped one template rhythm"))
        assertTrue(record.validationWarnings.any { it.contains("swapped one template rhythm") })
    }

    private fun output(
        status: String,
        verdict: VerifierVerdict,
    ): AiChapterRewriteDraftOutput {
        val beforeTexts =
            listOf(
                "A pause.",
                "A calculation.",
                "A prediction.",
                "The long sentence that carries the scene forward with detail and movement goes here.",
                "Short again.",
                "Another one.",
                "Third short.",
                "End.",
            )
        val beforeBlocks =
            beforeTexts.mapIndexed { index, text ->
                ChapterBlock("b%04d".format(index + 1), "p", "<p>$text</p>", false, "prose")
            }
        val afterBlocks =
            (1..8).map { index ->
                ChapterBlock(
                    "c%04d".format(index),
                    "p",
                    "<p>A single uniform medium sentence carries the beat onward.</p>",
                    false,
                    "rewritten",
                )
            }
        val comparison =
            ChapterCadenceReport.compare(
                ChapterCadenceReport.cadenceOf(beforeBlocks),
                ChapterCadenceReport.cadenceOf(afterBlocks),
            )
        return AiChapterRewriteDraftOutput(
            storyId = "rr_1",
            chapterId = "ch1",
            chapterTitle = "Chapter 1",
            polishedHtml = "<p>Polished.</p>\n",
            validation =
                RewriteValidationResult(
                    ok = true,
                    warnings = listOf(RewriteIssue("severe_shrink", "b0004: 40->5 words")),
                    mergedCount = 31,
                ),
            verdict = verdict,
            cadenceComparison = comparison,
            model = "openai/gpt-5.6-terra",
            verifierModel = "x-ai/grok-4.6",
            promptVersion = "v1.2-light",
            strengthWire = "light",
            operationId = "op-1",
            providerTier = "strict",
            costUsd = "0.158",
            status = status,
            sourceSha256 = "sha-source",
        )
    }
}
