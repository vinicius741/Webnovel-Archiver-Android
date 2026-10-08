package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonPost
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatreonChapterSyncTest {
    private val link =
        PatreonEarlyAccessLink(campaignId = "3805883", collectionId = "2030713", collectionTitle = "The Lich King Reincarnates")
    private val publicChapters =
        listOf(
            Chapter(id = "1", title = "Book 4 Chapter 13: Prestigious Acquisitions", downloaded = true),
            Chapter(id = "2", title = "Book 4 Chapter 14: Subversion", downloaded = true),
        )

    @Test
    fun `selection keeps only chapter posts newer than the newest public chapter`() {
        val posts =
            listOf(
                post("17", "Novel - Book 4 Chapter 16: Burden"),
                post("16", "Novel: Book 2 is out!"),
                post("15", "Novel - Book 4 Chapter 15: Ascent"),
                post("14", "Novel - Book 4 Chapter 14: Subversion"),
                post("13", "Novel - Book 4 Chapter 13: Prestigious Acquisitions"),
            )

        val selection = PatreonChapterPlanning.selectEarlyAccess(posts, PatreonCopyPlanning.publicKeys(publicChapters))

        assertTrue(selection.anchorFound)
        assertEquals(listOf("15", "17"), selection.ahead.map { it.id })
    }

    @Test
    fun `selection without any public match adds nothing`() {
        val selection =
            PatreonChapterPlanning.selectEarlyAccess(
                listOf(post("1", "Other Novel - Chapter 900: Unknown")),
                PatreonCopyPlanning.publicKeys(publicChapters),
            )
        assertFalse(selection.anchorFound)
        assertTrue(selection.ahead.isEmpty())
    }

    @Test
    fun `plan stores readable new posts, keeps stored copies, and counts locked ones`() {
        val stored = copy("15", "Book 4 Chapter 15: Ascent")
        val missingFile = copy("16", "Book 4 Chapter 16: Burden").copy(downloaded = false, filePath = null)
        val selection =
            PatreonChapterPlanning.EarlyAccessSelection(
                ahead =
                    listOf(
                        post("15", "Novel - Book 4 Chapter 15: Ascent"),
                        post("16", "Novel - Book 4 Chapter 16: Burden"),
                        post("17", "Novel - Book 4 Chapter 17: Crown"),
                        post("18", "Novel - Book 4 Chapter 18: Locked", canView = false),
                    ),
                anchorFound = true,
            )

        val plan = PatreonChapterPlanning.planChapters(listOf(stored, missingFile), selection)

        assertEquals(listOf("patreon_15"), plan.keep.map { it.id })
        assertEquals(listOf("16", "17"), plan.toStore.map { it.id })
        assertEquals(1, plan.lockedCount)
    }

    @Test
    fun `sync pages until it reaches a public chapter and stores readable posts`() =
        runBlocking {
            val pages =
                mapOf(
                    null to
                        page(
                            listOf(post("17", "Novel - Book 4 Chapter 16: Burden"), post("15", "Novel - Book 4 Chapter 15: Ascent")),
                            next = "c2",
                        ),
                    "c2" to page(listOf(post("14", "Novel - Book 4 Chapter 14: Subversion")), next = "c3"),
                )
            val requested = mutableListOf<String>()
            val saved = mutableListOf<Pair<Int, String>>()
            val sync =
                PatreonChapterSync(
                    api =
                        PatreonApi { url ->
                            requested += url
                            val cursor = Regex("page%5Bcursor%5D=([^&]+)").find(url)?.groupValues?.get(1)
                            pages.getValue(cursor)
                        },
                    sessionPresent = { true },
                    cleanup = { it },
                    saveChapter = { _, index, chapter, html ->
                        saved += index to chapter.title
                        assertTrue(html.contains("<p>"))
                        "novels/s/${chapter.title}.html"
                    },
                    now = { 1_000L },
                )

            val result = sync.sync("s", link, publicChapters, emptyList()) {}

            assertEquals(2, requested.size)
            assertEquals(listOf("patreon_15", "patreon_17"), result.chapters.map { it.id })
            assertEquals(listOf("Book 4 Chapter 15: Ascent", "Book 4 Chapter 16: Burden"), result.chapters.map { it.title })
            assertTrue(result.chapters.all { it.downloaded && it.isPatreonCopy && it.downloadedAt == 1_000L })
            assertEquals(listOf(2 to "patreon_15", 3 to "patreon_17"), saved)
            assertEquals("novels/s/patreon_15.html", result.chapters.first().filePath)
            assertEquals(1_000L, result.link.lastCheckedAt)
            assertNull(result.link.lastError)
            assertEquals(0, result.link.lockedCount)
        }

    @Test
    fun `locked posts while signed out report a sign-in hint`() =
        runBlocking {
            val sync =
                PatreonChapterSync(
                    api =
                        PatreonApi {
                            page(
                                listOf(
                                    post("15", "Novel - Book 4 Chapter 15: Ascent", canView = false),
                                    post("14", "Book 4 Chapter 14: Subversion"),
                                ),
                            )
                        },
                    sessionPresent = { false },
                    cleanup = { it },
                    saveChapter = { _, _, _, _ -> error("nothing is readable") },
                )

            val result = sync.sync("s", link, publicChapters, emptyList()) {}

            assertTrue(result.chapters.isEmpty())
            assertEquals(1, result.link.lockedCount)
            assertEquals("Sign in to Patreon to read 1 early-access chapter.", result.link.lastError)
        }

    @Test
    fun `a rejected session keeps existing copies and records the error`() =
        runBlocking {
            val existing = listOf(copy("15", "Book 4 Chapter 15: Ascent"))
            val sync =
                PatreonChapterSync(
                    api = PatreonApi { url -> throw HttpNetworkException(url, 401) },
                    sessionPresent = { true },
                    cleanup = { it },
                    saveChapter = { _, _, _, _ -> error("not reached") },
                )

            val result = sync.sync("s", link, publicChapters, existing) {}

            assertEquals(existing, result.chapters)
            assertEquals("Patreon sign-in expired. Paste a new session in Settings.", result.link.lastError)
        }

    private fun post(
        id: String,
        title: String,
        canView: Boolean = true,
    ) = PatreonPost(
        id = id,
        title = title,
        publishedAt = id.toLong() * 1_000L,
        canView = canView,
        richContent = if (canView) richDoc("Body $id") else null,
    )

    private fun copy(
        postId: String,
        title: String,
    ) = Chapter(
        id = PatreonCopyPlanning.chapterId(postId),
        title = title,
        url = PatreonCopyPlanning.postUrl(postId),
        downloaded = true,
        filePath = "novels/s/patreon_$postId.html",
        publishedAt = postId.toLong() * 1_000L,
        patreonPostId = postId,
    )

    private fun page(
        posts: List<PatreonPost>,
        next: String? = null,
    ): String {
        val data =
            posts.joinToString(",") { post ->
                val content = post.richContent?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "null"
                val publishedAt = "2026-10-0${post.id.last()}T10:00:00.000+00:00"
                """{"id":"${post.id}","type":"post","attributes":{"title":"${post.title}","published_at":"$publishedAt",""" +
                    """"current_user_can_view":${post.canView},"content_json_string":$content}}"""
            }
        val cursor = next?.let { "\"$it\"" } ?: "null"
        return """{"data":[$data],"meta":{"pagination":{"total":99,"cursors":{"next":$cursor}}}}"""
    }

    private fun richDoc(text: String) = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""
}
