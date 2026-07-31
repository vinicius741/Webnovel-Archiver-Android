package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.source.SourceProvider

/**
 * Owns the stable identity rules shared by full/latest sync merges and the concurrent-write fold.
 * The first local chapter wins when malformed or duplicate source data resolves to one identity,
 * matching the historical merge behavior.
 */
internal class ChapterMatcher(
    private val provider: SourceProvider,
) {
    data class Index(
        val byStableId: Map<String, Chapter>,
        val aliases: Map<String, String>,
        val originalIndexByStableId: Map<String, Int>,
    )

    fun stableId(chapter: Chapter): String = provider.getChapterId(chapter.url) ?: chapter.id.ifBlank { chapter.url }

    fun stableId(info: ChapterInfo): String = provider.getChapterId(info.url) ?: info.id ?: info.url

    fun index(chapters: List<Chapter>): Index {
        val byStableId = linkedMapOf<String, Chapter>()
        val aliases = mutableMapOf<String, String>()
        val originalIndexByStableId = linkedMapOf<String, Int>()
        chapters.forEachIndexed { index, chapter ->
            val stable = stableId(chapter)
            if (stable.isBlank()) return@forEachIndexed
            if (byStableId.putIfAbsent(stable, chapter) == null) {
                originalIndexByStableId[stable] = index
            }
            if (chapter.id.isNotBlank()) aliases[chapter.id] = stable
            if (chapter.url.isNotBlank()) aliases[chapter.url] = stable
        }
        return Index(byStableId, aliases, originalIndexByStableId)
    }

    fun match(
        info: ChapterInfo,
        index: Index,
    ): Chapter? = index.byStableId[stableId(info)]
}
