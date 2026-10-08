package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning.ChapterKey
import com.vinicius741.webnovelarchiver.source.PatreonPost

/**
 * Chooses which posts of a linked Patreon collection are early access: those newer than the
 * newest post the public source already has. Matching rules live in [PatreonCopyPlanning].
 */
object PatreonChapterPlanning {
    data class EarlyAccessSelection(
        /** Posts newer than the newest public chapter, oldest first. */
        val ahead: List<PatreonPost>,
        /** False when no fetched post lined up with a public chapter, so nothing can be trusted as ahead. */
        val anchorFound: Boolean,
    )

    data class PatreonChapterPlan(
        /** Existing Patreon copies to keep as they are. */
        val keep: List<Chapter>,
        /** Readable early-access posts whose text must be stored (new, or a copy missing its file). */
        val toStore: List<PatreonPost>,
        /** Early-access posts this session cannot read. */
        val lockedCount: Int,
    )

    /** Splits newest-first collection posts at the newest one already published on the source. */
    fun selectEarlyAccess(
        newestFirst: List<PatreonPost>,
        publicKeys: List<Pair<Chapter, ChapterKey>>,
    ): EarlyAccessSelection {
        val chapterPosts = newestFirst.filter { PatreonCopyPlanning.isChapterPost(it.title) }
        val anchor = chapterPosts.indexOfFirst { PatreonCopyPlanning.publicMatch(it.title, publicKeys) != null }
        if (anchor < 0) return EarlyAccessSelection(emptyList(), anchorFound = false)
        return EarlyAccessSelection(chapterPosts.take(anchor).asReversed(), anchorFound = true)
    }

    fun planChapters(
        existingPatreon: List<Chapter>,
        selection: EarlyAccessSelection,
    ): PatreonChapterPlan {
        val existingByPost = existingPatreon.associateBy { it.patreonPostId }
        val toStore = mutableListOf<PatreonPost>()
        var locked = 0
        selection.ahead.forEach { post ->
            val existing = existingByPost[post.id]
            val readable = post.canView && post.richContent != null
            when {
                existing != null && existing.downloaded -> Unit
                readable -> toStore += post
                existing == null -> locked += 1
            }
        }
        val replacedPosts = toStore.map { it.id }.toSet()
        return PatreonChapterPlan(
            keep = existingPatreon.filterNot { it.patreonPostId in replacedPosts },
            toStore = toStore,
            lockedCount = locked,
        )
    }

    /** Orders Patreon copies by publication so the tail reads in release order. */
    fun orderPatreonChapters(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedWith(
            compareBy<Chapter>({ it.publishedAt ?: Long.MAX_VALUE }, { it.patreonPostId?.toLongOrNull() ?: Long.MAX_VALUE }),
        )
}
