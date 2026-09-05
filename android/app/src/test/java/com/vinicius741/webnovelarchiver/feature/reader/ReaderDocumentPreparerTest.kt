package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterContentVersion
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentPreparerTest {
    @Test
    fun preparesSanitizedAnnotatedCopyAndWebViewPayloadThroughInjectedDispatchers() =
        runTest {
            val chapter = Chapter(id = "chapter-1", title = "First", content = "<p>Hello world.</p><script>bad()</script>")
            val story = Story(id = "story-1", title = "Story", chapters = mutableListOf(chapter))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val preparer =
                ReaderDocumentPreparer(
                    source = FakeReaderDocumentSource(story),
                    ioDispatcher = dispatcher,
                    computationDispatcher = dispatcher,
                )

            val preparation =
                preparer.prepare(
                    story.id,
                    chapter.id,
                    ReaderDocumentPalette(
                        normal = ReaderDocumentColors("#FFFFFF", "#000000"),
                        forcedDark = ReaderDocumentColors("#121212", "#E6E6E6"),
                    ),
                )
            val document = (preparation as? ReaderPreparation.Ready)?.document ?: error("Expected ready document")

            assertEquals(0, document.chapterIndex)
            assertEquals("Hello world.", document.formattedText.trim())
            assertEquals(listOf("Hello world."), document.annotated.chunks)
            assertFalse(document.annotated.annotatedHtml.contains("<script"))
            assertTrue(document.webViewHtml.contains("data-tts-group"))
            assertTrue(document.webViewHtml.contains("#121212"))
        }

    @Test
    fun preservesBreakSeparatedForumParagraphsFromExistingDownloads() =
        runTest {
            val content =
                "Header line one<br>Header line two<br><br>" +
                    "First prose paragraph.<br><br>Second prose paragraph."
            val chapter = Chapter(id = "chapter-1", title = "First", content = content)
            val story = Story(id = "story-1", title = "Story", chapters = mutableListOf(chapter))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val preparer =
                ReaderDocumentPreparer(
                    source = FakeReaderDocumentSource(story),
                    ioDispatcher = dispatcher,
                    computationDispatcher = dispatcher,
                )

            val preparation =
                preparer.prepare(
                    story.id,
                    chapter.id,
                    ReaderDocumentPalette(
                        normal = ReaderDocumentColors("#FFFFFF", "#000000"),
                        forcedDark = ReaderDocumentColors("#121212", "#E6E6E6"),
                    ),
                )
            val document = (preparation as? ReaderPreparation.Ready)?.document ?: error("Expected ready document")
            val paragraphs = Jsoup.parse(document.webViewHtml).select("body > p")

            assertEquals(3, paragraphs.size)
            assertEquals(1, paragraphs[0].select("br").size)
            assertTrue(paragraphs[1].text().contains("First prose paragraph"))
            assertTrue(paragraphs[2].text().contains("Second prose paragraph"))
        }

    @Test
    fun unknownStoryOrChapterReportsMissingInsteadOfNull() =
        runTest {
            val chapter = Chapter(id = "chapter-1", title = "First", content = "<p>Hello world.</p>")
            val story = Story(id = "story-1", title = "Story", chapters = mutableListOf(chapter))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val preparer =
                ReaderDocumentPreparer(
                    source = FakeReaderDocumentSource(story),
                    ioDispatcher = dispatcher,
                    computationDispatcher = dispatcher,
                )
            val palette =
                ReaderDocumentPalette(
                    normal = ReaderDocumentColors("#FFFFFF", "#000000"),
                    forcedDark = ReaderDocumentColors("#121212", "#E6E6E6"),
                )

            val missingStory = preparer.prepare("unknown-story", chapter.id, palette)
            val missingChapter = preparer.prepare(story.id, "unknown-chapter", palette)

            assertTrue(missingStory is ReaderPreparation.Missing)
            assertTrue(missingChapter is ReaderPreparation.Missing)
        }

    @Test
    fun unreadableChapterContentReportsFailedWithTheCause() =
        runTest {
            val chapter = Chapter(id = "chapter-1", title = "First")
            val story = Story(id = "story-1", title = "Story", chapters = mutableListOf(chapter))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val preparer =
                ReaderDocumentPreparer(
                    source =
                        object : ReaderDocumentSource by FakeReaderDocumentSource(story) {
                            override suspend fun resolvedContent(
                                storyId: String,
                                chapter: Chapter,
                            ): ResolvedChapterContent = throw java.io.IOException("disk unreadable")
                        },
                    ioDispatcher = dispatcher,
                    computationDispatcher = dispatcher,
                )

            val preparation =
                preparer.prepare(
                    story.id,
                    chapter.id,
                    ReaderDocumentPalette(
                        normal = ReaderDocumentColors("#FFFFFF", "#000000"),
                        forcedDark = ReaderDocumentColors("#121212", "#E6E6E6"),
                    ),
                )

            val failed = preparation as? ReaderPreparation.Failed ?: error("Expected failed preparation")
            assertEquals("disk unreadable", failed.cause.message)
        }

    private class FakeReaderDocumentSource(
        private val story: Story,
    ) : ReaderDocumentSource {
        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override suspend fun resolvedContent(
            storyId: String,
            chapter: Chapter,
        ): ResolvedChapterContent = ResolvedChapterContent(chapter.content, ChapterContentVersion.SOURCE, false, null)

        override fun ttsSettings() = TtsSettings()

        override fun regexRules(): List<RegexCleanupRule> = emptyList()

        override fun displayPreferences() = DisplayPreferences(readerDark = true)

        override fun ttsSession(): TtsSession? = null
    }
}
