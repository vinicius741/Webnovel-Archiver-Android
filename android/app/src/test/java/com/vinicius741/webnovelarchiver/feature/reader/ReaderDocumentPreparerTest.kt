package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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

            val document =
                preparer.prepare(
                    story.id,
                    chapter.id,
                    ReaderDocumentPalette(
                        normal = ReaderDocumentColors("#FFFFFF", "#000000"),
                        forcedDark = ReaderDocumentColors("#121212", "#E6E6E6"),
                    ),
                ) ?: error("Expected document")

            assertEquals(0, document.chapterIndex)
            assertEquals("Hello world.", document.formattedText.trim())
            assertEquals(listOf("Hello world."), document.annotated.chunks)
            assertFalse(document.annotated.annotatedHtml.contains("<script"))
            assertTrue(document.webViewHtml.contains("data-tts-group"))
            assertTrue(document.webViewHtml.contains("#121212"))
        }

    private class FakeReaderDocumentSource(
        private val story: Story,
    ) : ReaderDocumentSource {
        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override suspend fun chapterHtml(chapter: Chapter): String? = chapter.content

        override fun ttsSettings() = TtsSettings()

        override fun regexRules(): List<RegexCleanupRule> = emptyList()

        override fun displayPreferences() = DisplayPreferences(readerDark = true)

        override fun ttsSession(): TtsSession? = null
    }
}
