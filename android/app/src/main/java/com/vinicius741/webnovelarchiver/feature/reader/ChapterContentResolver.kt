package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.ai.ChapterBlockParsing
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.appliedChapterRewrite
import com.vinicius741.webnovelarchiver.data.repository.appliedRewriteHtml
import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterContentVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The chapter text a consumer should use, and which local variant it came from. */
internal data class ResolvedChapterContent(
    val html: String?,
    val version: ChapterContentVersion,
    /** True when the source chapter changed after this rewrite was generated. Still served. */
    val stale: Boolean,
    /** The active applied rewrite when the polished variant is being served; null otherwise. */
    val applied: AppliedChapterRewrite?,
    /** Any applied rewrite for the chapter, even an inactive one (reader badge/toggle affordance). */
    val availableApplied: AppliedChapterRewrite? = applied,
)

/** Read-only rewrite lookup the resolver consults; the repository backs it in production. */
internal interface ChapterRewriteLookup {
    fun appliedRewrite(
        storyId: String,
        chapterId: String,
    ): AppliedChapterRewrite?

    fun appliedHtml(record: AppliedChapterRewrite): String?

    suspend fun sourceChapterHtml(chapter: Chapter): String?
}

internal class RepositoryChapterRewriteLookup(
    private val repository: AppRepository,
) : ChapterRewriteLookup {
    override fun appliedRewrite(
        storyId: String,
        chapterId: String,
    ): AppliedChapterRewrite? = repository.appliedChapterRewrite(storyId, chapterId)

    override fun appliedHtml(record: AppliedChapterRewrite): String? = repository.appliedRewriteHtml(record.storyId, record.chapterId)

    override suspend fun sourceChapterHtml(chapter: Chapter): String? = repository.readChapter(chapter)
}

/**
 * Single source of truth for Source versus Polished chapter content. Reader, formatted copy, and
 * TTS all resolve through this seam so the text on screen and the text read aloud can never
 * diverge. An applied-and-active rewrite wins; a source whose hash changed keeps serving the
 * polished variant (never switch text under the reader) but is flagged stale.
 */
internal class ChapterContentResolver(
    private val rewrites: ChapterRewriteLookup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(repository: AppRepository) : this(RepositoryChapterRewriteLookup(repository))

    suspend fun resolve(
        storyId: String,
        chapter: Chapter,
    ): ResolvedChapterContent =
        withContext(ioDispatcher) {
            val applied = rewrites.appliedRewrite(storyId, chapter.id)
            if (applied != null && applied.active) {
                val polished = rewrites.appliedHtml(applied)
                if (!polished.isNullOrEmpty()) {
                    val sourceHtml = rewrites.sourceChapterHtml(chapter)
                    val stale =
                        sourceHtml?.let { ChapterBlockParsing.parseChapter(it).sourceSha256 != applied.sourceSha256 }
                            ?: false
                    return@withContext ResolvedChapterContent(polished, ChapterContentVersion.POLISHED, stale, applied, applied)
                }
            }
            ResolvedChapterContent(rewrites.sourceChapterHtml(chapter), ChapterContentVersion.SOURCE, false, null, applied)
        }
}
