package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.RoyalRoadProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Retention of the per-novel Chapter polish strength across sync: it is local-only state (the
 * source knows nothing about it), so both the fresh synced Story and the concurrent-change fold
 * must carry it forward — the same contract as aiDescription/aiCoverPath/aiContextChapterIndices.
 */

class StorySyncRewriteRetentionTest {
    @Test
    fun foldKeepsOnDiskStrengthWhenUserPickedDuringSyncWindow() {
        val synced = syncedStory(strength = null)
        val onDisk = syncedStory(strength = "balanced")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("balanced", folded.chapterRewriteStrength)
    }

    @Test
    fun foldKeepsExplicitStrengthResetMadeDuringSyncWindow() {
        // The synced snapshot still carries the pre-window strength, but the disk record re-read
        // at commit is null (user reset it mid-window): null wins, not the stale value (R04).
        val synced = syncedStory(strength = "light")
        val onDisk = syncedStory(strength = null)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertNull(folded.chapterRewriteStrength)
    }

    @Test
    fun legacyStoryWithoutStrengthFieldStaysNull() {
        val folded =
            StorySyncMergePlanning.foldConcurrentChanges(
                syncedStory(strength = null),
                syncedStory(strength = null),
                RoyalRoadProvider,
            )

        assertNull(folded.chapterRewriteStrength)
    }

    @Test
    fun engineCarryForwardKeepsStrengthOnFreshStory() {
        // The engine's Story(...) construction carries the field from `existing`; the fold is the
        // only other writer. Asserting the planning-level contract here: a null on disk may never
        // resurrect a stale value, and a picked value may never be reset by metadata churn.
        val synced = syncedStory(strength = "balanced")
        val onDisk = syncedStory(strength = "balanced")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("balanced", folded.chapterRewriteStrength)
    }

    private fun syncedStory(strength: String?): Story =
        Story(
            id = "story",
            title = "Synced",
            author = "Author",
            sourceUrl = "https://www.royalroad.com/fiction/1/story",
            chapters = mutableListOf(Chapter(id = "10", title = "Chapter 10")),
            chapterRewriteStrength = strength,
        )
}
