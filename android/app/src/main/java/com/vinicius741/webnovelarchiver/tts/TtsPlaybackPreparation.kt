package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.feature.reader.ChapterContentResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedTtsPlayback(
    val story: Story,
    val chapter: Chapter,
    val settings: TtsSettings,
    val chunks: List<String>,
    val startIndex: Int,
)

internal interface TtsPlaybackSource {
    fun story(id: String): Story?

    /** Variant-aware chapter read (Source vs Polished) so narration never diverges from Reader. */
    suspend fun chapterHtml(
        storyId: String,
        chapter: Chapter,
    ): String?

    fun settings(): TtsSettings

    fun regexRules(): List<RegexCleanupRule>

    fun session(): TtsSession?

    suspend fun position(storyId: String): TtsStoryPosition?

    /** Persists last-read progress and keeps any in-memory library cache coherent. */
    suspend fun markChapterRead(
        storyId: String,
        chapterId: String,
    )
}

/** Performs all TTS file reads and chunk parsing away from Android's main dispatcher. */
internal class TtsPlaybackPreparer(
    private val source: TtsPlaybackSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        repository: AppRepository,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(RepositoryTtsPlaybackSource(repository), ioDispatcher, computationDispatcher)

    /**
     * [requestedIndex] null means "play wherever the story last stopped" (podcast resume); a value
     * pins the start (double-tapped paragraph, restart-from-top). Resume may target a different
     * chapter than the one requested.
     */
    suspend fun prepare(
        storyId: String,
        chapterId: String,
        requestedIndex: Int? = null,
    ): PreparedTtsPlayback? {
        if (requestedIndex == null) {
            resumeFromStoryPosition(storyId, chapterId)?.let { return it }
        }
        val input = load(storyId, chapterId) ?: return null
        return build(input, requestedIndex ?: 0)
    }

    private suspend fun resumeFromStoryPosition(
        storyId: String,
        requestedChapterId: String,
    ): PreparedTtsPlayback? {
        val start =
            withContext(ioDispatcher) {
                val story = source.story(storyId) ?: return@withContext null
                TtsSessionPlanning.resolveStartPosition(story, requestedChapterId, source.position(storyId))
            } ?: return null
        // A stale position (chapter unreadable) returns null and the caller falls back to the requested chapter.
        val input = load(storyId, start.chapterId) ?: return null
        return build(input, start.chunkIndex)
    }

    suspend fun resume(): PreparedTtsPlayback? {
        val persisted = withContext(ioDispatcher) { source.session() } ?: return null
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return null
        val input = load(persisted.storyId, persisted.chapterId) ?: return null
        val prepared = build(input, persisted.currentChunkIndex) ?: return null
        return prepared.copy(startIndex = TtsSessionPlanning.boundedChunkIndex(persisted, prepared.chunks.size))
    }

    suspend fun nextChapter(session: TtsSession): PreparedTtsPlayback? {
        // Description narration has no following chapter; finish playback instead of marking the
        // sentinel id as last-read (which would corrupt the story's reading position).
        if (TtsDescriptionPlanning.isDescriptionSession(session.chapterId)) return null
        val target =
            withContext(ioDispatcher) {
                val story = source.story(session.storyId) ?: return@withContext null
                source.markChapterRead(session.storyId, session.chapterId)
                val nextIndex = TtsSessionPlanning.nextChapterIndex(story, session.chapterId) ?: return@withContext null
                story to story.chapters[nextIndex]
            } ?: return null
        val input = load(target.first.id, target.second.id) ?: return null
        return build(input, 0)
    }

    /** Manual chapter skip ([delta] is -1 or +1); null when no chapter exists in that direction. */
    suspend fun chapterAt(
        session: TtsSession,
        delta: Int,
    ): PreparedTtsPlayback? {
        if (TtsDescriptionPlanning.isDescriptionSession(session.chapterId)) return null
        val target =
            withContext(ioDispatcher) {
                val story = source.story(session.storyId) ?: return@withContext null
                val targetIndex = TtsSessionPlanning.chapterIndexAtDelta(story, session.chapterId, delta) ?: return@withContext null
                if (delta > 0) source.markChapterRead(session.storyId, session.chapterId)
                story to story.chapters[targetIndex]
            } ?: return null
        val input = load(target.first.id, target.second.id) ?: return null
        return build(input, 0)
    }

    private suspend fun load(
        storyId: String,
        chapterId: String,
    ): PreparationInput? =
        withContext(ioDispatcher) {
            val story = source.story(storyId) ?: return@withContext null
            if (TtsDescriptionPlanning.isDescriptionSession(chapterId)) {
                // Description narration: the sentinel chapter id speaks the story's description
                // through the same chunking/settings pipeline as chapter playback. Reads whichever
                // synopsis the Details screen displays (source or AI-generated).
                val description =
                    AiDescriptionPlanning.activeDescription(story)?.takeIf { it.isNotBlank() }
                        ?: return@withContext null
                return@withContext PreparationInput(
                    story = story,
                    chapter = TtsDescriptionPlanning.descriptionChapter(),
                    html = TtsDescriptionPlanning.descriptionSessionHtml(story.title, description),
                    settings = source.settings(),
                    rules = source.regexRules(),
                )
            }
            val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return@withContext null
            PreparationInput(
                story = story,
                chapter = chapter,
                html = source.chapterHtml(storyId, chapter) ?: chapter.content,
                settings = source.settings(),
                rules = source.regexRules(),
            )
        }

    private suspend fun build(
        input: PreparationInput,
        requestedIndex: Int,
    ): PreparedTtsPlayback? =
        withContext(computationDispatcher) {
            val html = input.html ?: return@withContext null
            val chunks = TtsTextPreparation.prepareTtsChunks(html, input.rules)
            if (chunks.isEmpty()) return@withContext null
            PreparedTtsPlayback(
                story = input.story,
                chapter = input.chapter,
                settings = input.settings,
                chunks = chunks,
                startIndex = requestedIndex.coerceIn(0, chunks.lastIndex),
            )
        }
}

private data class PreparationInput(
    val story: Story,
    val chapter: Chapter,
    val html: String?,
    val settings: TtsSettings,
    val rules: List<RegexCleanupRule>,
)

/** Production source: repository owns story/session/settings state and chapter reads. */
private class RepositoryTtsPlaybackSource(
    private val repository: AppRepository,
) : TtsPlaybackSource {
    private val contentResolver = ChapterContentResolver(repository)

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun chapterHtml(
        storyId: String,
        chapter: Chapter,
    ): String? = contentResolver.resolve(storyId, chapter).html

    override fun settings(): TtsSettings = repository.getTtsSettings()

    override fun regexRules(): List<RegexCleanupRule> = repository.getRegexRules()

    override fun session(): TtsSession? = repository.getTtsSession()

    override suspend fun position(storyId: String): TtsStoryPosition? = repository.getTtsStoryPosition(storyId)

    override suspend fun markChapterRead(
        storyId: String,
        chapterId: String,
    ) {
        repository.setLastReadChapter(storyId, chapterId)
    }
}
