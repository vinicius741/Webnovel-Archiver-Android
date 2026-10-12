package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning.ChapterKey
import com.vinicius741.webnovelarchiver.source.PatreonPost
import java.math.BigDecimal

/**
 * Chooses which posts of a linked Patreon collection are early access: those newer than the
 * newest post the public source already has. Matching rules live in [PatreonCopyPlanning].
 *
 * A link to every post on the campaign (creators without collections) may also see the creator's
 * other novels, so its anchor must agree on the subtitle too, and a post ahead must share the
 * anchor's title prefix and continue its numbering.
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
        allPosts: Boolean = false,
    ): EarlyAccessSelection {
        val chapterPosts = newestFirst.filter { PatreonCopyPlanning.isChapterPost(it.title) }
        val anchor = chapterPosts.indexOfFirst { PatreonCopyPlanning.publicMatch(it.title, publicKeys, strict = allPosts) != null }
        if (anchor < 0) return EarlyAccessSelection(emptyList(), anchorFound = false)
        val ahead = chapterPosts.take(anchor)
        val kept = if (allPosts) ahead.filter { continuesAnchor(it.title, chapterPosts[anchor].title, ahead.size) } else ahead
        return EarlyAccessSelection(kept.asReversed(), anchorFound = true)
    }

    /**
     * Whether a post on an all-posts link reads as this novel's next chapter: same title prefix as
     * the anchor and, within the same book, numbered after it by at most the posts in between plus
     * a small gap. Unnumbered chapters (Interlude) and a new book only need the prefix.
     */
    private fun continuesAnchor(
        title: String,
        anchorTitle: String,
        postsAhead: Int,
    ): Boolean {
        if (PatreonCopyPlanning.titlePrefix(title) != PatreonCopyPlanning.titlePrefix(anchorTitle)) return false
        val key = PatreonCopyPlanning.chapterKey(title)
        val anchor = PatreonCopyPlanning.chapterKey(anchorTitle)
        val number = key.number?.toBigDecimalOrNull() ?: return true
        val anchorNumber = anchor.number?.toBigDecimalOrNull() ?: return true
        if (key.book != anchor.book) return key.book == null || anchor.book == null || key.book > anchor.book
        return number > anchorNumber && number <= anchorNumber + BigDecimal(postsAhead + MAX_NUMBERING_GAP)
    }

    /** Chapters an author may skip or renumber between two posts before a number reads as another novel's. */
    private const val MAX_NUMBERING_GAP = 5

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
