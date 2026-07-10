package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
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

    fun chapterHtml(chapter: Chapter): String?

    fun settings(): TtsSettings

    fun regexRules(): List<RegexCleanupRule>

    fun session(): TtsSession?

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
        storage: AppStorage,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(RepositoryTtsPlaybackSource(repository, storage), ioDispatcher, computationDispatcher)

    constructor(
        storage: AppStorage,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(StorageTtsPlaybackSource(storage), ioDispatcher, computationDispatcher)

    suspend fun prepare(
        storyId: String,
        chapterId: String,
        requestedIndex: Int = 0,
    ): PreparedTtsPlayback? {
        val input = load(storyId, chapterId) ?: return null
        return build(input, requestedIndex)
    }

    suspend fun resume(): PreparedTtsPlayback? {
        val persisted = withContext(ioDispatcher) { source.session() } ?: return null
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return null
        val input = load(persisted.storyId, persisted.chapterId) ?: return null
        val prepared = build(input, persisted.currentChunkIndex) ?: return null
        return prepared.copy(startIndex = TtsSessionPlanning.boundedChunkIndex(persisted, prepared.chunks.size))
    }

    suspend fun nextChapter(session: TtsSession): PreparedTtsPlayback? {
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

    private suspend fun load(
        storyId: String,
        chapterId: String,
    ): PreparationInput? =
        withContext(ioDispatcher) {
            val story = source.story(storyId) ?: return@withContext null
            val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return@withContext null
            PreparationInput(
                story = story,
                chapter = chapter,
                html = source.chapterHtml(chapter) ?: chapter.content,
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
            val chunks = TextCleanup.prepareTtsChunks(html, input.rules, input.settings.chunkSize)
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

/** Production source: repository owns story cache/publish; storage still serves chapter HTML. */
private class RepositoryTtsPlaybackSource(
    private val repository: AppRepository,
    private val storage: AppStorage,
) : TtsPlaybackSource {
    override fun story(id: String): Story? = repository.getStory(id)

    override fun chapterHtml(chapter: Chapter): String? = storage.readChapter(chapter)

    override fun settings(): TtsSettings = repository.getTtsSettings()

    override fun regexRules(): List<RegexCleanupRule> = repository.getRegexRules()

    override fun session(): TtsSession? = repository.getTtsSession()

    override suspend fun markChapterRead(
        storyId: String,
        chapterId: String,
    ) {
        repository.setLastReadChapter(storyId, chapterId)
    }
}

/** Storage-only fallback for tests and call sites that do not hold an [AppRepository]. */
private class StorageTtsPlaybackSource(
    private val storage: AppStorage,
) : TtsPlaybackSource {
    override fun story(id: String): Story? = storage.getStory(id)

    override fun chapterHtml(chapter: Chapter): String? = storage.readChapter(chapter)

    override fun settings(): TtsSettings = storage.getTtsSettings()

    override fun regexRules(): List<RegexCleanupRule> = storage.getRegexRules()

    override fun session(): TtsSession? = storage.getTtsSession()

    override suspend fun markChapterRead(
        storyId: String,
        chapterId: String,
    ) {
        synchronized(storage) {
            val latest = storage.getStory(storyId) ?: return
            latest.lastReadChapterId = chapterId
            storage.addOrUpdateStory(latest)
        }
    }
}
