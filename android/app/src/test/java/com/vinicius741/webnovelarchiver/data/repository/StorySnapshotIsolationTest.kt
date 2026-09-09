package com.vinicius741.webnovelarchiver.data.repository

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class StorySnapshotIsolationTest {
    @Test
    fun immutableSourceStateRetainsLegacyWireNamesAndNormalization() {
        val gson = Gson()
        val json =
            """
            {"availability":"not_found","lastCheckedAt":123,"unavailableSince":100,
             "consecutiveNotFoundCount":2,"lastFailure":"not_found","lastHttpStatus":404}
            """.trimIndent()
        val state = gson.fromJson(json, SourceSyncState::class.java)
        assertEquals(
            com.google.gson.JsonParser
                .parseString(json),
            gson.toJsonTree(state),
        )
        val snapshot = StoryMutations.snapshot(Story(sourceSyncState = state))
        org.junit.Assert.assertSame(state, snapshot.sourceSyncState)
        val changed = snapshot.copy(sourceSyncState = state.copy(lastCheckedAt = 200))
        assertEquals(123L, snapshot.sourceSyncState.lastCheckedAt)
        assertEquals(200L, changed.sourceSyncState.lastCheckedAt)
        assertEquals(SourceSyncState(), gson.fromJson("{}", SourceSyncState::class.java))
    }

    @Test
    fun everyMutableNestedCollectionIsDetached() {
        val original =
            Story(
                chapters = mutableListOf(Chapter(id = "one")),
                epubPaths = mutableListOf("one.epub"),
                tags = mutableListOf("tag"),
                pendingNewChapterIds = mutableListOf("one"),
                aiContextChapterIndices = mutableListOf(0),
                aiCoverContextChapterIndices = mutableListOf(1),
                patreonStats = PatreonRawStats(tiers = mutableListOf(PatreonRawTier(usdCents = 500), PatreonRawTier(usdCents = 1000))),
                sourceMetadata =
                    SourceMetadata(
                        metrics = mutableListOf(SourceMetric()),
                        contentWarnings = mutableListOf("warning"),
                        genres = mutableListOf("genre"),
                        fandoms = mutableListOf("fandom"),
                        characters = mutableListOf("character"),
                        ratingDistribution = mutableMapOf(5 to 10),
                    ),
            )
        val gson = Gson()
        val before = gson.toJsonTree(original)
        val snapshot = StoryMutations.snapshot(original)
        snapshot.chapters.first().title = "changed"
        snapshot.chapters.clear()
        snapshot.epubPaths!!.clear()
        snapshot.tags!!.clear()
        snapshot.pendingNewChapterIds!!.clear()
        snapshot.aiContextChapterIndices!!.clear()
        snapshot.aiCoverContextChapterIndices!!.clear()
        (snapshot.patreonStats!!.tiers as MutableList).clear()
        snapshot.sourceMetadata.apply {
            metrics.clear()
            contentWarnings.clear()
            genres.clear()
            fandoms.clear()
            characters.clear()
            ratingDistribution.clear()
        }
        assertEquals(before, gson.toJsonTree(original))
    }
}
