package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.math.BigDecimal

/**
 * Pure rules for Patreon early-access copies inside a story's chapter list. Titles are parsed into
 * book/chapter keys so a copy can be matched to the public source chapter that later publishes it.
 * Copies always sit after every source chapter; each is dropped once its public chapter has
 * downloaded, and a bookmark on a dropped copy moves to that public chapter.
 */
object PatreonCopyPlanning {
    /** Chapter identity parsed from a title: book and chapter numbers plus a normalized subtitle. */
    data class ChapterKey(
        val book: Int?,
        val number: String?,
        val subtitle: String,
    )

    data class Reconciled(
        val chapters: List<Chapter>,
        val removed: List<Chapter>,
        val lastReadChapterId: String?,
    )

    const val CHAPTER_ID_PREFIX = "patreon_"
    private const val MIN_SUBTITLE_MATCH_LENGTH = 4

    private val chapterToken = Regex("""\b(?:chapter|ch)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val bookToken = Regex("""\b(?:book|volume|vol\.?|b(?=\d))\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val unnumberedChapterWords =
        Regex("""\b(?:prologue|epilogue|interlude|side ?story|bonus chapter|extra chapter)\b""", RegexOption.IGNORE_CASE)
    private val trailingParenthetical = Regex("""\s*[(\[][^)\]]*[)\]]\s*$""")
    private val leadingSeparators = Regex("""^[\s:\-–—|.,]+""")
    private val nonAlphanumeric = Regex("""[^\p{L}\p{N}]+""")

    fun chapterId(postId: String): String = "$CHAPTER_ID_PREFIX$postId"

    fun postUrl(postId: String): String = "https://www.patreon.com/posts/$postId"

    fun chapterKey(title: String): ChapterKey {
        val text = title.replace('–', '-').replace('—', '-')
        val chapter = chapterToken.find(text)
        if (chapter == null) return ChapterKey(book = null, number = null, subtitle = normalize(text))
        // A book number only counts before the chapter token: "Chapter 62 (End of Book 1)" is book-less.
        val book =
            bookToken
                .findAll(text.substring(0, chapter.range.first))
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val subtitle = text.substring(chapter.range.last + 1).replace(leadingSeparators, "").replace(trailingParenthetical, "")
        return ChapterKey(book = book, number = normalizeNumber(chapter.groupValues[1]), subtitle = normalize(subtitle))
    }

    fun matches(
        a: ChapterKey,
        b: ChapterKey,
    ): Boolean {
        if (a.number != null && b.number != null) {
            if (a.number != b.number) return false
            return when {
                a.book != null && b.book != null -> a.book == b.book
                a.book == null && b.book == null -> true
                // One side omits the book (sources often restart numbering per book); require the
                // subtitle too so "Book 4 Chapter 14" never matches book one's "Chapter 14".
                else -> a.subtitle.isNotEmpty() && a.subtitle == b.subtitle
            }
        }
        return a.subtitle.length >= MIN_SUBTITLE_MATCH_LENGTH && a.subtitle == b.subtitle
    }

    /** Drops a leading novel-name prefix: "Novel Name - Book 4 Chapter 43: X" -> "Book 4 Chapter 43: X". */
    fun displayTitle(postTitle: String): String {
        val start =
            listOfNotNull(bookToken.find(postTitle)?.range?.first, chapterToken.find(postTitle)?.range?.first).minOrNull()
                ?: return postTitle.trim()
        val prefix = postTitle.substring(0, start).trimEnd()
        val stripped = if (start > 0 && prefix.lastOrNull() in setOf('-', ':', '|', '–', '—')) postTitle.substring(start) else postTitle
        return stripped.trim()
    }

    /** Whether a collection post reads as a chapter rather than an announcement. */
    fun isChapterPost(title: String): Boolean = chapterToken.containsMatchIn(title) || unnumberedChapterWords.containsMatchIn(title)

    /** The newest public chapter that [title] is a copy of, if any. */
    fun publicMatch(
        title: String,
        publicKeys: List<Pair<Chapter, ChapterKey>>,
    ): Chapter? {
        val key = chapterKey(title)
        return publicKeys.lastOrNull { (_, publicKey) -> matches(key, publicKey) }?.first
    }

    fun publicKeys(publicChapters: List<Chapter>): List<Pair<Chapter, ChapterKey>> =
        publicChapters.map { chapter -> chapter to chapterKey(chapter.title) }

    /**
     * Points every Patreon copy at the public chapter that publishes it, then drops copies whose
     * public chapter has downloaded. A bookmark on a dropped copy moves to its public chapter.
     */
    fun reconcile(
        chapters: List<Chapter>,
        lastReadChapterId: String?,
    ): Reconciled {
        if (chapters.none { it.isPatreonCopy }) return Reconciled(chapters, emptyList(), lastReadChapterId)
        val public = chapters.filterNot { it.isPatreonCopy }
        val publicById = public.associateBy { it.id }
        val keys = publicKeys(public)
        val removed = mutableListOf<Chapter>()
        val replacementOfRemoved = mutableMapOf<String, String>()
        val patreon =
            chapters.filter { it.isPatreonCopy }.mapNotNull { copy ->
                val replacement = publicMatch(copy.title, keys)
                if (replacement != null && publicById[replacement.id]?.downloaded == true) {
                    removed += copy
                    replacementOfRemoved[copy.id] = replacement.id
                    null
                } else {
                    copy.copy(patreonReplacedBy = replacement?.id)
                }
            }
        val lastRead = lastReadChapterId?.let { replacementOfRemoved[it] ?: it }
        return Reconciled(public + patreon, removed, lastRead)
    }

    /** [reconcile] applied to a whole story, keeping download counters and pending ids consistent. */
    fun reconcileStory(story: Story): Pair<Story, List<Chapter>> {
        val result = reconcile(story.chapters, story.lastReadChapterId)
        if (result.removed.isEmpty() && result.chapters == story.chapters) return story to emptyList()
        val chapters = result.chapters.toMutableList()
        val downloaded = chapters.count { it.downloaded }
        val removedIds = result.removed.map { it.id }.toSet()
        val status =
            when {
                result.removed.isEmpty() -> story.status
                chapters.isNotEmpty() && downloaded == chapters.size -> DownloadStatus.completed
                downloaded > 0 -> DownloadStatus.partial
                else -> story.status
            }
        return story.copy(
            chapters = chapters,
            downloadedChapters = downloaded,
            status = status,
            lastReadChapterId = result.lastReadChapterId,
            pendingNewChapterIds =
                story.pendingNewChapterIds
                    ?.filterNot { it in removedIds }
                    ?.toMutableList()
                    ?.ifEmpty { null },
        ) to result.removed
    }

    /**
     * Sets or clears the story's Patreon link. Switching to another collection or unlinking drops
     * every stored copy (they belong to the old collection); a bookmark on one moves to the public
     * chapter that publishes it, else to the last public chapter.
     */
    fun relink(
        story: Story,
        link: PatreonEarlyAccessLink?,
    ): Pair<Story, List<Chapter>> {
        if (link != null && link.collectionId == story.patreonEarlyAccess?.collectionId) {
            return story.copy(patreonEarlyAccess = link.copy(lastCheckedAt = story.patreonEarlyAccess?.lastCheckedAt)) to emptyList()
        }
        val removed = story.chapters.filter { it.isPatreonCopy }
        val chapters = story.chapters.filterNot { it.isPatreonCopy }.toMutableList()
        val removedIds = removed.map { it.id }.toSet()
        val lastRead =
            story.lastReadChapterId?.let { id ->
                if (id !in removedIds) {
                    id
                } else {
                    removed.first { it.id == id }.patreonReplacedBy?.takeIf { target -> chapters.any { it.id == target } }
                        ?: chapters.lastOrNull()?.id
                }
            }
        val downloaded = chapters.count { it.downloaded }
        return story.copy(
            patreonEarlyAccess = link,
            chapters = chapters,
            downloadedChapters = downloaded,
            status =
                when {
                    removed.isEmpty() -> story.status
                    chapters.isNotEmpty() && downloaded == chapters.size -> DownloadStatus.completed
                    downloaded > 0 -> DownloadStatus.partial
                    else -> DownloadStatus.idle
                },
            lastReadChapterId = lastRead,
            epubStale = if (removed.isEmpty()) story.epubStale else true,
            pendingNewChapterIds =
                story.pendingNewChapterIds
                    ?.filterNot { it in removedIds }
                    ?.toMutableList()
                    ?.ifEmpty { null },
        ) to removed
    }

    private fun normalize(text: String): String = text.lowercase().replace(nonAlphanumeric, " ").trim()

    private fun normalizeNumber(value: String): String =
        runCatching { BigDecimal(value).stripTrailingZeros().toPlainString() }.getOrDefault(value)
}
