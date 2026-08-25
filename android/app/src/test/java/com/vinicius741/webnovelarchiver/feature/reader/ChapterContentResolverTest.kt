package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.ai.ChapterBlockParsing
import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterContentVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentResolverTest {
    @Test
    fun `no rewrite serves the immutable source`() =
        runBlocking {
            val resolver = ChapterContentResolver(FakeLookup(), Dispatchers.Unconfined)
            val content = resolver.resolve(STORY, chapter())
            assertEquals(ChapterContentVersion.SOURCE, content.version)
            assertEquals(SOURCE_HTML, content.html)
            assertFalse(content.stale)
            assertNull(content.applied)
        }

    @Test
    fun `active applied rewrite serves polished html`() =
        runBlocking {
            val applied = applied(active = true, sourceSha = sha(SOURCE_HTML))
            val resolver = ChapterContentResolver(FakeLookup(applied = applied), Dispatchers.Unconfined)
            val content = resolver.resolve(STORY, chapter())
            assertEquals(ChapterContentVersion.POLISHED, content.version)
            assertEquals(POLISHED_HTML, content.html)
            assertFalse(content.stale)
            assertEquals(applied, content.applied)
        }

    @Test
    fun `inactive rewrite falls back to source without deleting anything`() =
        runBlocking {
            val resolver =
                ChapterContentResolver(
                    FakeLookup(applied = applied(active = false, sourceSha = sha(SOURCE_HTML))),
                    Dispatchers.Unconfined,
                )
            val content = resolver.resolve(STORY, chapter())
            assertEquals(ChapterContentVersion.SOURCE, content.version)
            assertEquals(SOURCE_HTML, content.html)
        }

    @Test
    fun `source changed after generation keeps serving polished but flags stale`() =
        runBlocking {
            val resolver =
                ChapterContentResolver(
                    FakeLookup(applied = applied(active = true, sourceSha = "an-old-hash")),
                    Dispatchers.Unconfined,
                )
            val content = resolver.resolve(STORY, chapter())
            assertEquals(ChapterContentVersion.POLISHED, content.version)
            assertEquals(POLISHED_HTML, content.html)
            assertTrue(content.stale)
        }

    @Test
    fun `missing applied file falls back to source`() =
        runBlocking {
            val resolver =
                ChapterContentResolver(
                    FakeLookup(applied = applied(active = true, sourceSha = sha(SOURCE_HTML)), html = null),
                    Dispatchers.Unconfined,
                )
            val content = resolver.resolve(STORY, chapter())
            assertEquals(ChapterContentVersion.SOURCE, content.version)
            assertEquals(SOURCE_HTML, content.html)
        }

    private fun chapter() = Chapter(id = CHAPTER, title = "One", downloaded = true)

    private fun sha(html: String): String = ChapterBlockParsing.parseChapter(html).sourceSha256

    private fun applied(
        active: Boolean,
        sourceSha: String,
    ): AppliedChapterRewrite =
        AppliedChapterRewrite(
            storyId = STORY,
            chapterId = CHAPTER,
            sourceSha256 = sourceSha,
            active = active,
            fileStem = "stem",
        )

    private class FakeLookup(
        val applied: AppliedChapterRewrite? = null,
        val html: String? = POLISHED_HTML,
    ) : ChapterRewriteLookup {
        override fun appliedRewrite(
            storyId: String,
            chapterId: String,
        ): AppliedChapterRewrite? = applied?.takeIf { it.storyId == storyId && it.chapterId == chapterId }

        override fun appliedHtml(record: AppliedChapterRewrite): String? = html

        override suspend fun sourceChapterHtml(chapter: Chapter): String? = SOURCE_HTML
    }

    private companion object {
        const val STORY = "story1"
        const val CHAPTER = "ch1"
        const val SOURCE_HTML = "<p>Original source prose.</p><blockquote><strong>[SYSTEM]</strong></blockquote>"
        const val POLISHED_HTML = "<p>Polished source prose, merged.</p><blockquote><strong>[SYSTEM]</strong></blockquote>"
    }
}
