package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterContentVersion
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
    /** Which local variant [annotated]/[formattedText]/[webViewHtml] were built from. */
    val contentVersion: ChapterContentVersion = ChapterContentVersion.SOURCE,
    val contentStale: Boolean = false,
    /** An applied rewrite exists even when the source variant is being shown (badge/toggle). */
    val hasAppliedRewrite: Boolean = false,
)

/**
 * Explicit preparation outcome (R12): missing ids, a read failure, and success are distinct states
 * so the screen can render a message instead of an eternal spinner.
 */
internal sealed interface ReaderPreparation {
    data class Ready(
        val document: ReaderDocument,
    ) : ReaderPreparation

    data object Missing : ReaderPreparation

    data class Failed(
        val cause: Throwable,
    ) : ReaderPreparation
}

internal interface ReaderDocumentSource {
    fun story(id: String): Story?

    /** The variant-aware chapter content; production resolves Source vs Polished here. */
    suspend fun resolvedContent(
        storyId: String,
        chapter: Chapter,
    ): ResolvedChapterContent

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
    ): ReaderPreparation {
        val startedAt = System.nanoTime() / 1_000_000L
        val result =
            prepareInternal(storyId, chapterId, palette)
        com.vinicius741.webnovelarchiver.data.diagnostics.LocalDiagnostics.recordOperation(
            "reader_prepare",
            System.nanoTime() / 1_000_000L - startedAt,
            failed = result is ReaderPreparation.Failed,
        )
        return result
    }

    private suspend fun prepareInternal(
        storyId: String,
        chapterId: String,
        palette: ReaderDocumentPalette,
    ): ReaderPreparation {
        val input =
            try {
                withContext(ioDispatcher) {
                    val story = source.story(storyId) ?: return@withContext null
                    val chapterIndex = story.chapters.indexOfFirst { it.id == chapterId }
                    if (chapterIndex < 0) return@withContext null
                    val chapter = story.chapters[chapterIndex]
                    val resolved = source.resolvedContent(storyId, chapter)
                    ReaderDocumentInput(
                        story = story,
                        chapter = chapter,
                        chapterIndex = chapterIndex,
                        rawContent = resolved.html ?: chapter.content,
                        contentVersion = resolved.version,
                        contentStale = resolved.stale,
                        hasAppliedRewrite = resolved.availableApplied != null,
                        settings = source.ttsSettings(),
                        rules = source.regexRules(),
                        display = source.displayPreferences(),
                        persistedSession = source.ttsSession(),
                    )
                }
            } catch (error: java.io.IOException) {
                return ReaderPreparation.Failed(error)
            } ?: return ReaderPreparation.Missing

        return withContext(computationDispatcher) {
            val rawContent = ReaderContentRenderer.contentOrUndownloadedMessage(input.rawContent)
            val annotated =
                TtsTextPreparation.prepareTtsAnnotatedHtml(
                    ChapterHtmlSanitizer.sanitize(rawContent),
                    input.rules,
                )
            val colors = if (input.display.readerDark) palette.forcedDark else palette.normal
            ReaderPreparation.Ready(
                ReaderDocument(
                    story = input.story,
                    chapter = input.chapter,
                    chapterIndex = input.chapterIndex,
                    annotated = annotated,
                    formattedText = HtmlCleanup.htmlToFormattedText(rawContent),
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
                    contentVersion = input.contentVersion,
                    contentStale = input.contentStale,
                    hasAppliedRewrite = input.hasAppliedRewrite,
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
    val contentVersion: ChapterContentVersion,
    val contentStale: Boolean,
    val hasAppliedRewrite: Boolean,
    val settings: TtsSettings,
    val rules: List<RegexCleanupRule>,
    val display: DisplayPreferences,
    val persistedSession: TtsSession?,
)

private class RepositoryReaderDocumentSource(
    private val repository: AppRepository,
) : ReaderDocumentSource {
    private val contentResolver = ChapterContentResolver(repository)

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun resolvedContent(
        storyId: String,
        chapter: Chapter,
    ): ResolvedChapterContent = contentResolver.resolve(storyId, chapter)

    override fun ttsSettings(): TtsSettings = repository.getTtsSettings()

    override fun regexRules(): List<RegexCleanupRule> = repository.getRegexRules()

    override fun displayPreferences(): DisplayPreferences = repository.getDisplayPreferences()

    override fun ttsSession(): TtsSession? = repository.getTtsSession()
}
