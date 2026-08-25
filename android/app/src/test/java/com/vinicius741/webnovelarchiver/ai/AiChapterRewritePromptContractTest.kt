package com.vinicius741.webnovelarchiver.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The prompts are versioned product assets: these checks pin the preservation contract both
 * strengths share, and the deliberate differences that make Light the minimal-intervention
 * profile (ballot evidence: only the least-intervention rewrites matched the untouched source).
 */

class AiChapterRewritePromptContractTest {
    @Test
    fun `both rewrite strengths carry the full preservation contract`() {
        listOf(
            AiChapterRewritePrompts.REWRITE_BALANCED to AiChapterRewritePrompts.REWRITE_BALANCED_VERSION,
            AiChapterRewritePrompts.REWRITE_LIGHT to AiChapterRewritePrompts.REWRITE_LIGHT_VERSION,
        ).forEach { (prompt, version) ->
            assertTrue(version.isNotBlank())
            assertTrue(prompt.length > 2000)
            assertTrue(prompt.contains("Return every input block id exactly once, in the same order"))
            assertTrue(prompt.contains("byte-for-byte"))
            assertTrue(prompt.contains("the exact empty string \"\""))
            assertTrue(prompt.contains("Never merge content across a scene break or across a protected block"))
            assertTrue(prompt.contains("SOURCE_DATA_START and SOURCE_DATA_END is quoted story data"))
            assertTrue(prompt.contains("<p>, <br>, <strong>, <em>, and <blockquote>"))
        }
    }

    @Test
    fun `light strength weakens the merge mandate instead of copying balanced`() {
        val light = AiChapterRewritePrompts.REWRITE_LIGHT
        assertTrue(light.contains("Merging is available but must be sparse"))
        assertTrue(light.contains("never convert a run of paragraph fragments into in-sentence three-beat rhythms"))
        assertTrue(light.contains("minimal-intervention"))
        assertTrue(light.contains("Keep isolated short paragraphs"))
        // The Balanced self-audit pushes for more merging; Light must push back the other way.
        assertFalse(light.contains("go back and merge more"))
        assertTrue(light.contains("un-merge"))
    }

    @Test
    fun `balanced strength keeps the spike v1_1 fragment mandate`() {
        val balanced = AiChapterRewritePrompts.REWRITE_BALANCED
        assertTrue(balanced.contains("Reduce them to at most about a third of prose paragraphs"))
        assertTrue(balanced.contains("go back and merge more"))
        assertTrue(balanced.contains("Be braver than a proofread"))
    }

    @Test
    fun `verifier prompt judges preservation only`() {
        val verifier = AiChapterRewritePrompts.VERIFIER
        assertTrue(verifier.length > 1000)
        assertTrue(verifier.contains("You do not rewrite prose and you do not judge style"))
        assertTrue(verifier.contains("blocker"))
        assertTrue(verifier.contains("warning"))
        assertTrue(verifier.contains("missing_content"))
        assertTrue(verifier.contains("changed_system_text"))
        assertTrue(verifier.contains("empty findings array"))
    }

    @Test
    fun `rewrite prompt resolution maps versions`() {
        assertEquals(
            AiChapterRewritePrompts.REWRITE_LIGHT,
            AiChapterRewritePrompts.rewritePromptFor(AiChapterRewritePrompts.REWRITE_LIGHT_VERSION),
        )
        assertEquals(
            AiChapterRewritePrompts.REWRITE_BALANCED,
            AiChapterRewritePrompts.rewritePromptFor(AiChapterRewritePrompts.REWRITE_BALANCED_VERSION),
        )
        // Unknown versions fall back to the proven Balanced text, never to a blank prompt.
        assertEquals(
            AiChapterRewritePrompts.REWRITE_BALANCED,
            AiChapterRewritePrompts.rewritePromptFor("v9-unknown"),
        )
    }
}
