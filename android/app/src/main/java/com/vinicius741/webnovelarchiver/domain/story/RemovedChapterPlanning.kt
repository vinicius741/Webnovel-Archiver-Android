package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Rules for downloaded chapters the source no longer lists. They stay in the story's own chapter
 * list, flagged by [Chapter.removedFromSourceAt], in the reading position they had before the
 * source dropped them, so the reader, TTS, and EPUBs read straight through them.
 */
object RemovedChapterPlanning {
    /**
     * Inserts [kept] into [current]. Each kept chapter goes right after the nearest chapter that
     * preceded it in [previous] and still exists in [current]; one with no surviving predecessor
     * goes first. Patreon copies never anchor a placement and stay last. [key] must give a chapter
     * the same identity in all three lists.
     */
    fun placeKept(
        previous: List<Chapter>,
        current: List<Chapter>,
        kept: List<Chapter>,
        key: (Chapter) -> String,
    ): List<Chapter> {
        if (kept.isEmpty()) return current
        val keptByKey = LinkedHashMap<String, Chapter>()
        kept.forEach { keptByKey.putIfAbsent(key(it), it) }
        val currentKeys = current.mapTo(HashSet(), key)
        val head = mutableListOf<Chapter>()
        val after = HashMap<String, MutableList<Chapter>>()
        var anchor: String? = null
        previous.forEach { chapter ->
            val chapterKey = key(chapter)
            val row = keptByKey.takeIf { chapterKey !in currentKeys }?.remove(chapterKey)
            val currentAnchor = anchor
            when {
                row != null && currentAnchor == null -> head += row
                row != null && currentAnchor != null -> after.getOrPut(currentAnchor) { mutableListOf() } += row
                chapterKey in currentKeys && !chapter.isPatreonCopy -> anchor = chapterKey
            }
        }
        val source = current.filterNot { it.isPatreonCopy }
        return buildList {
            addAll(head)
            source.forEach { chapter ->
                add(chapter)
                after.remove(key(chapter))?.let { rows -> addAll(rows) }
            }
            // Anchors that only matched a duplicate identity, and kept rows absent from [previous].
            after.values.forEach { rows -> addAll(rows) }
            addAll(keptByKey.values)
            addAll(current.filter { it.isPatreonCopy })
        }
    }

    /**
     * Re-points the story's position-based settings (EPUB range, AI context picks) from [before]
     * to [after] by chapter identity, so inserting or removing rows never shifts them onto other
     * chapters. An EPUB range that reached the end keeps reaching it.
     */
    fun remapPositions(
        story: Story,
        before: List<Chapter>,
        after: List<Chapter>,
    ): Story {
        if (before.map { it.id } == after.map { it.id }) return story
        val newIndexById = HashMap<String, Int>()
        after.forEachIndexed { index, chapter -> newIndexById.putIfAbsent(chapter.id, index) }

        fun remapIndices(indices: List<Int>?): MutableList<Int>? =
            indices
                ?.mapNotNull { index -> before.getOrNull(index)?.id?.let(newIndexById::get) }
                ?.distinct()
                ?.sorted()
                ?.toMutableList()
                ?.ifEmpty { null }

        val config =
            story.epubConfig?.takeIf { after.isNotEmpty() }?.let { config ->
                val start =
                    (
                        before
                            .getOrNull(config.rangeStart - 1)
                            ?.id
                            ?.let(newIndexById::get)
                            ?.plus(1) ?: config.rangeStart
                    ).coerceIn(1, after.size)
                val end =
                    if (config.rangeEnd >= before.size) {
                        after.size
                    } else {
                        before
                            .getOrNull(config.rangeEnd - 1)
                            ?.id
                            ?.let(newIndexById::get)
                            ?.plus(1) ?: config.rangeEnd
                    }.coerceIn(start, after.size)
                config.copy(rangeStart = start, rangeEnd = end)
            } ?: story.epubConfig
        return story.copy(
            epubConfig = config,
            aiContextChapterIndices = remapIndices(story.aiContextChapterIndices),
            aiCoverContextChapterIndices = remapIndices(story.aiCoverContextChapterIndices),
        )
    }

    /**
     * Safety net for writes that replace a story's chapter list (a sync commit): every downloaded
     * source chapter of [before] that [after] no longer holds is put back, flagged removed from the
     * source (keeping an earlier flag), in its old reading position. Downloaded text is never dropped
     * implicitly; only an explicit [discard] removes it. Patreon copies are excluded because a sync
     * legitimately retires them. Returns [after] unchanged (same instance) when nothing was lost.
     */
    fun restoreLostDownloads(
        before: Story,
        after: Story,
        key: (Chapter) -> String,
        removedAt: Long,
    ): Story {
        val afterKeys = after.chapters.mapTo(HashSet(), key)
        val lost =
            before.chapters
                .filter { chapter ->
                    chapter.downloaded &&
                        !chapter.isPatreonCopy &&
                        (chapter.filePath != null || chapter.content != null) &&
                        key(chapter) !in afterKeys
                }.map { it.copy(removedFromSourceAt = it.removedFromSourceAt ?: removedAt) }
        if (lost.isEmpty()) return after
        val chapters = placeKept(before.chapters, after.chapters, lost, key).toMutableList()
        val hasEpub = after.epubPath != null || !after.epubPaths.isNullOrEmpty()
        return remapPositions(after, after.chapters, chapters).copy(
            chapters = chapters,
            totalChapters = chapters.size,
            downloadedChapters = chapters.count { it.downloaded },
            lastReadChapterId = after.lastReadChapterId ?: before.lastReadChapterId?.takeIf { id -> lost.any { it.id == id } },
            epubStale = if (hasEpub) true else after.epubStale,
        )
    }

    data class Discarded(
        val story: Story,
        val removed: List<Chapter>,
    )

    /**
     * Drops the removed-from-source chapters among [chapterIds] (all of them when null). Chapters
     * the source still lists are never touched. A bookmark on a dropped chapter moves to the
     * nearest earlier chapter that stays.
     */
    fun discard(
        story: Story,
        chapterIds: Set<String>? = null,
    ): Discarded {
        val drop = story.chapters.filter { it.isRemovedFromSource && (chapterIds == null || it.id in chapterIds) }
        if (drop.isEmpty()) return Discarded(story, emptyList())
        val dropIds = drop.mapTo(HashSet()) { it.id }
        val remaining = story.chapters.filterNot { it.id in dropIds }.toMutableList()
        val bookmark =
            story.lastReadChapterId?.let { id ->
                if (id !in dropIds) {
                    id
                } else {
                    story.chapters
                        .take(story.chapters.indexOfFirst { it.id == id }.coerceAtLeast(0))
                        .lastOrNull { it.id !in dropIds }
                        ?.id
                }
            }
        val hasEpub = story.epubPath != null || !story.epubPaths.isNullOrEmpty()
        val updated =
            remapPositions(story, story.chapters, remaining).copy(
                chapters = remaining,
                totalChapters = remaining.size,
                downloadedChapters = remaining.count { it.downloaded },
                lastReadChapterId = bookmark,
                epubStale = if (hasEpub) true else story.epubStale,
            )
        return Discarded(updated, drop)
    }
}
