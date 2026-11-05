package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry

/** A saved place in a chapter, by sentence number plus the text that was there. */
internal data class TtsSavedPoint(
    val chunkIndex: Int,
    val offsetMs: Long = 0L,
    val anchorText: String? = null,
    val chunkingVersion: Int = TtsTextPreparation.CHUNKING_VERSION,
)

internal data class TtsChunkPoint(
    val chunkIndex: Int,
    val offsetMs: Long = 0L,
)

/** Resolves saved places against the chapter's current text, and decides how much to replay. */
internal object TtsPositionPlanning {
    const val ANCHOR_LENGTH = 48
    const val MAX_HISTORY = 10

    private const val MS_30_SECONDS = 30_000L
    private const val MS_10_MINUTES = 10L * 60_000L
    private const val MS_6_HOURS = 6L * 3_600_000L
    private const val MS_3_DAYS = 3L * 24L * 3_600_000L

    fun anchorFor(chunk: String?): String? = chunk?.take(ANCHOR_LENGTH)

    /**
     * Finds where a saved place is now. Text beats numbering: edits and cleanup changes can shift
     * the numbers, so the nearest sentence with the saved opening wins. A place saved before anchors
     * existed is first translated through the old numbering ([legacyChunks]). The offset is kept only
     * when the very same sentence is found.
     */
    fun resolve(
        chunks: List<String>,
        saved: TtsSavedPoint,
        legacyChunks: () -> List<String>,
    ): TtsChunkPoint {
        if (chunks.isEmpty()) return TtsChunkPoint(0)
        val clamped = saved.chunkIndex.coerceIn(0, chunks.lastIndex)
        if (saved.anchorText.isNullOrEmpty()) {
            if (saved.chunkingVersion >= TtsTextPreparation.CHUNKING_VERSION || saved.chunkIndex == 0) return TtsChunkPoint(clamped)
            return TtsChunkPoint(fromLegacyNumbering(chunks, saved.chunkIndex, legacyChunks()) ?: clamped)
        }
        if (chunks[clamped].startsWith(saved.anchorText)) return TtsChunkPoint(clamped, saved.offsetMs.coerceAtLeast(0L))
        return TtsChunkPoint(nearestIndex(chunks, clamped) { it.startsWith(saved.anchorText) } ?: clamped)
    }

    private fun fromLegacyNumbering(
        chunks: List<String>,
        legacyIndex: Int,
        legacyChunks: List<String>,
    ): Int? {
        val text = legacyChunks.getOrNull(legacyIndex) ?: return null
        return nearestIndex(chunks, legacyIndex.coerceIn(0, chunks.lastIndex)) { it.contains(text) }
    }

    private fun nearestIndex(
        chunks: List<String>,
        around: Int,
        matches: (String) -> Boolean,
    ): Int? {
        for (distance in 0..chunks.size) {
            val before = around - distance
            val after = around + distance
            if (before in chunks.indices && matches(chunks[before])) return before
            if (after in chunks.indices && matches(chunks[after])) return after
        }
        return null
    }

    /**
     * Where to pick up after a pause: a brief stop continues exactly where it was, and longer breaks
     * step back further so the story's thread can be picked up again.
     */
    fun resumePoint(
        chunks: List<TtsNarrationChunk>,
        point: TtsChunkPoint,
        pausedForMs: Long,
        contextReplay: Boolean,
    ): TtsChunkPoint {
        if (chunks.isEmpty() || !contextReplay || pausedForMs < MS_30_SECONDS) return point
        val index = point.chunkIndex.coerceIn(0, chunks.lastIndex)
        val target =
            when {
                pausedForMs < MS_10_MINUTES -> index
                pausedForMs < MS_6_HOURS -> (index - 1).coerceAtLeast(0)
                pausedForMs < MS_3_DAYS -> paragraphStart(chunks, index)
                else -> paragraphStart(chunks, (paragraphStart(chunks, index) - 1).coerceAtLeast(0))
            }
        return TtsChunkPoint(target)
    }

    private fun paragraphStart(
        chunks: List<TtsNarrationChunk>,
        index: Int,
    ): Int {
        var start = index
        while (start > 0 && chunks[start - 1].boundaryAfter == TtsChunkBoundary.SENTENCE) start--
        return start
    }

    /** Newest first; a repeat of the same place replaces the older entry. */
    fun withHistoryEntry(
        history: List<TtsHistoryEntry>,
        entry: TtsHistoryEntry,
    ): List<TtsHistoryEntry> =
        (listOf(entry) + history.filterNot { it.chapterId == entry.chapterId && it.chunkIndex == entry.chunkIndex }).take(MAX_HISTORY)
}
