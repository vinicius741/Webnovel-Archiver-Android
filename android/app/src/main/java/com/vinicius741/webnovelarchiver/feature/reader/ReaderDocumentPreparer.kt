package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReaderDocumentPalette(
    val normal: ReaderDocumentColors,
    val forcedDark: ReaderDocumentColors,
)

/** Complete immutable-enough Reader payload prepared before a WebView is created on main. */
internal data class ReaderDocument(
    val story: Story,
    val chapter: Chapter,
    val chapterIndex: Int,
    val annotated: TtsTextPreparation.TtsAnnotatedHtml,
    val formattedText: String,
    val display: DisplayPreferences,
    val persistedSession: TtsSession?,
    val colors: ReaderDocumentColors,
    val webViewHtml: String,
)

internal interface ReaderDocumentSource {
    fun story(id: String): Story?

    suspend fun chapterHtml(chapter: Chapter): String?

    fun ttsSettings(): TtsSettings

    fun regexRules(): List<RegexCleanupRule>

    fun displayPreferences(): DisplayPreferences

    fun ttsSession(): TtsSession?
}

/** Separates Reader disk access and HTML parsing from the main-thread WebView attachment step. */
internal class ReaderDocumentPreparer(
    private val source: ReaderDocumentSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        repository: AppRepository,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(RepositoryReaderDocumentSource(repository), ioDispatcher, computationDispatcher)

    suspend fun prepare(
        storyId: String,
        chapterId: String,
        palette: ReaderDocumentPalette,
    ): ReaderDocument? {
        val input =
            withContext(ioDispatcher) {
                val story = source.story(storyId) ?: return@withContext null
                val chapterIndex = story.chapters.indexOfFirst { it.id == chapterId }
                if (chapterIndex < 0) return@withContext null
                val chapter = story.chapters[chapterIndex]
                ReaderDocumentInput(
                    story = story,
                    chapter = chapter,
                    chapterIndex = chapterIndex,
                    rawContent = source.chapterHtml(chapter) ?: chapter.content,
                    settings = source.ttsSettings(),
                    rules = source.regexRules(),
                    display = source.displayPreferences(),
                    persistedSession = source.ttsSession(),
                )
            } ?: return null

        return withContext(computationDispatcher) {
            val rawContent = ReaderContentRenderer.contentOrUndownloadedMessage(input.rawContent)
            val annotated =
                TextCleanup.prepareTtsAnnotatedHtml(
                    ChapterHtmlSanitizer.sanitize(rawContent),
                    input.rules,
                    input.settings.chunkSize,
                )
            val colors = if (input.display.readerDark) palette.forcedDark else palette.normal
            ReaderDocument(
                story = input.story,
                chapter = input.chapter,
                chapterIndex = input.chapterIndex,
                annotated = annotated,
                formattedText = TextCleanup.htmlToFormattedText(rawContent),
                display = input.display,
                persistedSession = input.persistedSession,
                colors = colors,
                webViewHtml =
                    ReaderContentRenderer.document(
                        input.chapter.title,
                        annotated.annotatedHtml,
                        input.display.readerFontScale,
                        colors,
                        includeTtsScript = true,
                    ),
            )
        }
    }
}

private data class ReaderDocumentInput(
    val story: Story,
    val chapter: Chapter,
    val chapterIndex: Int,
    val rawContent: String?,
    val settings: TtsSettings,
    val rules: List<RegexCleanupRule>,
    val display: DisplayPreferences,
    val persistedSession: TtsSession?,
)

private class RepositoryReaderDocumentSource(
    private val repository: AppRepository,
) : ReaderDocumentSource {
    override fun story(id: String): Story? = repository.getStory(id)

    override suspend fun chapterHtml(chapter: Chapter): String? = repository.readChapter(chapter)

    override fun ttsSettings(): TtsSettings = repository.getTtsSettings()

    override fun regexRules(): List<RegexCleanupRule> = repository.getRegexRules()

    override fun displayPreferences(): DisplayPreferences = repository.getDisplayPreferences()

    override fun ttsSession(): TtsSession? = repository.getTtsSession()
}
