package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonPost
import com.vinicius741.webnovelarchiver.source.PatreonRichText
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.sanitizeTitle
import kotlinx.coroutines.CancellationException

/**
 * The Patreon step of a story sync: reads the linked collection newest-first until it reaches a
 * post the public source already has, then stores every readable early-access post as a
 * downloaded chapter. The post list carries full bodies, so no per-chapter request is needed.
 * Failures never fail the public sync; existing Patreon copies are kept and the link records why.
 */
class PatreonChapterSync(
    private val api: PatreonApi,
    private val sessionPresent: () -> Boolean,
    private val cleanup: (String) -> String,
    private val saveChapter: suspend (storyId: String, index: Int, chapter: Chapter, html: String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        /** Patreon copies in reading order, to append after the public chapters. */
        val chapters: List<Chapter>,
        val link: PatreonEarlyAccessLink,
        val storedChapterIds: List<String>,
    )

    @Suppress("TooGenericExceptionCaught") // Patreon is supplementary: any failure becomes a status on the link.
    suspend fun sync(
        storyId: String,
        link: PatreonEarlyAccessLink,
        publicChapters: List<Chapter>,
        existingPatreon: List<Chapter>,
        status: (String) -> Unit,
    ): Result {
        val checkedAt = now()
        return try {
            status("Checking Patreon early access...")
            val publicKeys = PatreonCopyPlanning.publicKeys(publicChapters)
            val posts = fetchUntilPublic(link, publicKeys)
            val selection = PatreonChapterPlanning.selectEarlyAccess(posts, publicKeys)
            val plan = PatreonChapterPlanning.planChapters(existingPatreon, selection)
            val created = plan.toStore.mapNotNull(::toChapter)
            val ordered = PatreonChapterPlanning.orderPatreonChapters(plan.keep + created.map { it.first })
            val htmlByPost = created.associate { (chapter, html) -> chapter.patreonPostId to html }
            val stored =
                ordered.mapIndexed { offset, chapter ->
                    val html = htmlByPost[chapter.patreonPostId] ?: return@mapIndexed chapter
                    // A Patreon-only file name can never collide with a public chapter's file.
                    val fileKey = chapter.copy(title = "${PatreonCopyPlanning.CHAPTER_ID_PREFIX}${chapter.patreonPostId}")
                    chapter.copy(filePath = saveChapter(storyId, publicChapters.size + offset, fileKey, html))
                }
            val unreadable = plan.toStore.size - created.size
            Result(
                chapters = stored,
                link =
                    link.copy(
                        lastCheckedAt = checkedAt,
                        lockedCount = plan.lockedCount + unreadable,
                        lastError = statusMessage(selection.anchorFound, plan.lockedCount + unreadable),
                    ),
                storedChapterIds = created.map { it.first.id },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message =
                if (error is HttpNetworkException && error.statusCode in setOf(401, 403)) {
                    "Patreon sign-in expired. Paste a new session in Settings."
                } else {
                    "Patreon check failed: ${error.message ?: error.javaClass.simpleName}"
                }
            Result(existingPatreon, link.copy(lastCheckedAt = checkedAt, lastError = message), emptyList())
        }
    }

    private suspend fun fetchUntilPublic(
        link: PatreonEarlyAccessLink,
        publicKeys: List<Pair<Chapter, PatreonCopyPlanning.ChapterKey>>,
    ): List<PatreonPost> {
        val posts = mutableListOf<PatreonPost>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.collectionPosts(link.campaignId, link.collectionId, cursor)
            posts += page.posts
            pages += 1
            cursor = page.nextCursor
            val reachedPublic =
                page.posts.any { post ->
                    PatreonCopyPlanning.isChapterPost(post.title) && PatreonCopyPlanning.publicMatch(post.title, publicKeys) != null
                }
        } while (!reachedPublic && cursor != null && pages < MAX_PAGES)
        return posts
    }

    private fun toChapter(post: PatreonPost): Pair<Chapter, String>? {
        val html = cleanup(PatreonRichText.toHtml(post.richContent ?: return null))
        if (html.isBlank()) return null
        val chapter =
            Chapter(
                id = PatreonCopyPlanning.chapterId(post.id),
                title = sanitizeTitle(PatreonCopyPlanning.displayTitle(post.title)),
                url = PatreonCopyPlanning.postUrl(post.id),
                downloaded = true,
                downloadedAt = now(),
                publishedAt = post.publishedAt,
                patreonPostId = post.id,
            )
        return chapter to html
    }

    private fun statusMessage(
        anchorFound: Boolean,
        locked: Int,
    ): String? =
        when {
            !anchorFound -> "No Patreon post matched a public chapter, so none were added."
            locked > 0 && !sessionPresent() -> "Sign in to Patreon to read $locked early-access ${plural(locked)}."
            locked > 0 -> "$locked early-access ${plural(locked)} locked for this Patreon account."
            else -> null
        }

    private fun plural(count: Int) = if (count == 1) "chapter" else "chapters"

    companion object {
        /** 200 posts: far beyond any early-access lead, and a bound on a first link's reads. */
        const val MAX_PAGES = 10
    }
}
