package com.vinicius741.webnovelarchiver.core

data class ChapterMergeResult(
    val chapters: List<Chapter>,
    val newChapterIds: List<String>,
    val removedChapters: List<Chapter>,
    val lastReadChapterId: String?,
)

object StorySyncPlanning {
    fun mergeChapters(
        existing: List<Chapter>,
        incoming: List<ChapterInfo>,
        provider: SourceProvider,
        lastRead: String?,
    ): ChapterMergeResult {
        val existingById = linkedMapOf<String, Chapter>()
        val aliases = mutableMapOf<String, String>()
        val remaining = mutableSetOf<String>()
        existing.forEach { chapter ->
            val stable = provider.getChapterId(chapter.url) ?: chapter.id.ifBlank { chapter.url }
            if (stable.isBlank()) return@forEach
            existingById.putIfAbsent(stable, chapter)
            remaining.add(stable)
            if (chapter.id.isNotBlank()) aliases[chapter.id] = stable
            if (chapter.url.isNotBlank()) aliases[chapter.url] = stable
        }

        val newIds = mutableListOf<String>()
        val chapters = incoming.map { info ->
            val stable = provider.getChapterId(info.url) ?: info.id ?: info.url
            val found = existingById[stable]
            if (found != null) {
                remaining.remove(stable)
                found.copy(id = stable, title = sanitizeTitle(info.title), url = info.url)
            } else {
                newIds.add(stable)
                Chapter(id = stable, title = sanitizeTitle(info.title), url = info.url, downloaded = false)
            }
        }

        var remappedLast = lastRead?.let { aliases[it] ?: it }
        if (remappedLast != null && chapters.none { it.id == remappedLast }) remappedLast = null
        val removed = remaining.mapNotNull { existingById[it] }
        return ChapterMergeResult(chapters, newIds, removed, remappedLast)
    }

    fun buildPendingNewChapterIds(
        existingPending: List<String>?,
        chapterIdsToAdd: List<String>,
        mergedChapters: List<Chapter>,
    ): MutableList<String>? {
        val chapterById = mergedChapters.associateBy { it.id }
        val pending = linkedSetOf<String>()
        existingPending.orEmpty().forEach { pending.add(it) }
        chapterIdsToAdd.forEach { pending.add(it) }

        val filtered = pending.filter { id ->
            val chapter = chapterById[id]
            chapter != null && !chapter.downloaded
        }
        return filtered.toMutableList().ifEmpty { null }
    }

    fun updateEpubConfigForSync(existing: Story?, nextChapterCount: Int): EpubConfig? {
        val config = existing?.epubConfig ?: return existing?.epubConfig
        if (nextChapterCount <= 0) return config

        val oldTotal = existing.chapters.size
        val hasNewChapters = nextChapterCount > oldTotal
        val wasAtEnd = config.rangeEnd >= oldTotal
        val nextStart = config.rangeStart.coerceIn(1, nextChapterCount)
        var nextEnd = config.rangeEnd

        if (hasNewChapters && wasAtEnd) {
            nextEnd = nextChapterCount
        }
        nextEnd = nextEnd.coerceIn(nextStart, nextChapterCount)

        return config.copy(rangeStart = nextStart, rangeEnd = nextEnd)
    }

    fun shouldMarkEpubStale(existing: Story?, nextChapterCount: Int): Boolean {
        if (existing == null) return false
        val hasEpub = existing.epubPath != null || !existing.epubPaths.isNullOrEmpty()
        return hasEpub && existing.chapters.size != nextChapterCount
    }
}
