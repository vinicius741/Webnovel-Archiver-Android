package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedTtsPlayback(
    val story: Story,
    val chapter: Chapter,
    val settings: TtsSettings,
    val chunks: List<String>,
    val startIndex: Int,
    val narration: List<TtsNarrationChunk> = chunks.map { TtsNarrationChunk(it) },
    /** Audio time already heard within the start sentence. */
    val startOffsetMs: Long = 0L,
    /** Said before the first sentence, when starting at the top of a chapter. */
    val spokenTitle: String? = null,
    val history: List<TtsHistoryEntry> = emptyList(),
    /** When a restored session last changed, so a later resume knows how long the break was. */
    val savedAt: Long? = null,
)

/**
 * Tri-state outcome of advancing past a chapter's last chunk. Only [NoNextChapter] proves the story
 * ended; [Unprepared] must keep the session resumable instead of wiping the saved position.
 */
internal sealed interface NextChapterOutcome {
    data class Prepared(
        val playback: PreparedTtsPlayback,
    ) : NextChapterOutcome

    data object NoNextChapter : NextChapterOutcome

    data object Unprepared : NextChapterOutcome
}

internal interface TtsPlaybackSource {
    fun story(id: String): Story?

    /** The downloaded chapter HTML, read the same way the Reader reads it. */
    suspend fun chapterHtml(
        storyId: String,
        chapter: Chapter,
    ): String?

    fun settings(): TtsSettings

    fun regexRules(): List<RegexCleanupRule>

    fun session(): TtsSession?

    suspend fun position(storyId: String): TtsStoryPosition?
}

/** Performs all TTS file reads and chunk parsing away from Android's main dispatcher. */
internal class TtsPlaybackPreparer(
    private val source: TtsPlaybackSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val builder = TtsPlaybackBuilder(computationDispatcher)

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
        // A pinned start is the listener choosing that exact sentence, so no title comes first.
        return builder.build(input, requestedIndex ?: 0, speakTitle = requestedIndex == null)
    }

    /** A saved place: resolved against the current text, so renumbering cannot move it elsewhere. */
    suspend fun prepareAt(
        storyId: String,
        chapterId: String,
        saved: TtsSavedPoint,
    ): PreparedTtsPlayback? {
        val input = load(storyId, chapterId) ?: return null
        return builder.buildAt(input, saved)
    }

    private suspend fun resumeFromStoryPosition(
        storyId: String,
        requestedChapterId: String,
    ): PreparedTtsPlayback? {
        var history = emptyList<TtsHistoryEntry>()
        var saved: TtsSavedPoint? = null
        val start =
            withContext(ioDispatcher) {
                val story = source.story(storyId) ?: return@withContext null
                val position = source.position(storyId)
                history = position?.history.orEmpty()
                val resolved = TtsSessionPlanning.resolveStartPosition(story, requestedChapterId, position)
                if (position != null && !position.caughtUp && resolved.chapterId == position.chapterId) {
                    saved =
                        TtsSavedPoint(
                            position.currentChunkIndex,
                            position.chunkOffsetMs,
                            position.anchorText,
                            position.chunkingVersion,
                        )
                }
                resolved
            } ?: return null
        // A stale position (chapter unreadable) returns null and the caller falls back to the requested chapter.
        val input = load(storyId, start.chapterId) ?: return null
        val prepared = saved?.let { builder.buildAt(input, it) } ?: builder.build(input, start.chunkIndex, speakTitle = true)
        return prepared?.copy(history = history)
    }

    suspend fun resume(): PreparedTtsPlayback? {
        val persisted = withContext(ioDispatcher) { source.session() } ?: return null
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return null
        val input = load(persisted.storyId, persisted.chapterId) ?: return null
        val saved = TtsSavedPoint(persisted.currentChunkIndex, persisted.chunkOffsetMs, persisted.anchorText, persisted.chunkingVersion)
        return builder.buildAt(input, saved)?.copy(history = persisted.history, savedAt = persisted.updatedAt)
    }

    /** The session as it was saved, for deciding whether a restart should play or stay silent. */
    suspend fun persistedSession(): TtsSession? =
        withContext(ioDispatcher) { source.session() }?.takeIf(TtsSessionPlanning::isResumeEligible)

    /** The next chapter's sentences for rendering ahead; marks nothing as read. */
    suspend fun upcomingTexts(session: TtsSession): List<String> {
        if (TtsDescriptionPlanning.isDescriptionSession(session.chapterId)) return emptyList()
        val next =
            withContext(ioDispatcher) {
                val story = source.story(session.storyId) ?: return@withContext null
                TtsSessionPlanning.nextChapterIndex(story, session.chapterId)?.let { story.chapters[it] }
            } ?: return emptyList()
        val input = load(session.storyId, next.id) ?: return emptyList()
        val prepared = builder.build(input, 0, speakTitle = true) ?: return emptyList()
        return listOfNotNull(prepared.spokenTitle) + prepared.chunks
    }

    suspend fun nextChapter(session: TtsSession): NextChapterOutcome {
        // Description narration has no following chapter; finish playback.
        // Playback never moves the bookmark: only an explicit bookmark action does.
        if (TtsDescriptionPlanning.isDescriptionSession(session.chapterId)) return NextChapterOutcome.NoNextChapter
        return when (val next = lookupNextChapter(session)) {
            NextChapterLookup.NoNextChapter -> NextChapterOutcome.NoNextChapter
            // A missing story or a current chapter dropped from the list is ambiguity, not a proven
            // end of story — the caller must keep the session resumable.
            NextChapterLookup.Ambiguous -> NextChapterOutcome.Unprepared
            is NextChapterLookup.Found -> {
                // Unreadable/blank/undownloaded next chapter: it exists, so never treat as end.
                val input = load(session.storyId, next.chapter.id) ?: return NextChapterOutcome.Unprepared
                NextChapterOutcome.Prepared(builder.build(input, 0, speakTitle = true) ?: return NextChapterOutcome.Unprepared)
            }
        }
    }

    private suspend fun lookupNextChapter(session: TtsSession): NextChapterLookup =
        withContext(ioDispatcher) {
            val story = source.story(session.storyId) ?: return@withContext NextChapterLookup.Ambiguous
            val current = story.chapters.indexOfFirst { it.id == session.chapterId }
            when {
                current < 0 -> NextChapterLookup.Ambiguous
                current >= story.chapters.lastIndex -> NextChapterLookup.NoNextChapter
                else -> NextChapterLookup.Found(story.chapters[current + 1])
            }
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
                story to story.chapters[targetIndex]
            } ?: return null
        val input = load(target.first.id, target.second.id) ?: return null
        return builder.build(input, 0, speakTitle = true)
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
}

/** Turns a chapter's HTML into something playable, on the computation dispatcher. */
private class TtsPlaybackBuilder(
    private val computationDispatcher: CoroutineDispatcher,
) {
    suspend fun buildAt(
        input: PreparationInput,
        saved: TtsSavedPoint,
    ): PreparedTtsPlayback? =
        withContext(computationDispatcher) {
            val html = input.html ?: return@withContext null
            val narration = TtsTextPreparation.prepareTtsNarration(html, input.rules)
            if (narration.isEmpty()) return@withContext null
            val point =
                TtsPositionPlanning.resolve(narration.map { it.text }, saved) {
                    TtsTextPreparation.legacyChunkTexts(html, input.rules)
                }
            prepared(input, narration, point, speakTitle = saved.chunkIndex == 0 && saved.offsetMs == 0L)
        }

    suspend fun build(
        input: PreparationInput,
        requestedIndex: Int,
        speakTitle: Boolean = false,
    ): PreparedTtsPlayback? =
        withContext(computationDispatcher) {
            val html = input.html ?: return@withContext null
            val narration = TtsTextPreparation.prepareTtsNarration(html, input.rules)
            if (narration.isEmpty()) return@withContext null
            prepared(input, narration, TtsChunkPoint(requestedIndex.coerceIn(0, narration.lastIndex)), speakTitle)
        }

    private fun prepared(
        input: PreparationInput,
        narration: List<TtsNarrationChunk>,
        point: TtsChunkPoint,
        speakTitle: Boolean,
    ): PreparedTtsPlayback {
        val announce =
            speakTitle &&
                point.chunkIndex == 0 &&
                input.settings.announceChapters &&
                !TtsDescriptionPlanning.isDescriptionSession(input.chapter.id)
        return PreparedTtsPlayback(
            story = input.story,
            chapter = input.chapter,
            settings = input.settings,
            chunks = narration.map { it.text },
            startIndex = point.chunkIndex,
            narration = narration,
            startOffsetMs = point.offsetMs,
            spokenTitle =
                if (announce) TtsNarrationPlanning.spokenChapterTitle(input.chapter.title, narration.first().text) else null,
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

private sealed interface NextChapterLookup {
    data class Found(
        val chapter: Chapter,
    ) : NextChapterLookup

    data object NoNextChapter : NextChapterLookup

    /** The story or the current chapter vanished mid-playback; not a provable end of story. */
    data object Ambiguous : NextChapterLookup
}

/** Production source: repository owns story/session/settings state and chapter reads. */
private class RepositoryTtsPlaybackSource(
    private val repository: AppRepository,
) : TtsPlaybackSource {
    override fun story(id: String): Story? = repository.story(id)

    override suspend fun chapterHtml(
        storyId: String,
        chapter: Chapter,
    ): String? = withContext(Dispatchers.IO) { repository.readChapter(chapter) }

    override fun settings(): TtsSettings = repository.ttsSettings.get()

    override fun regexRules(): List<RegexCleanupRule> = repository.regexRules.get()

    override fun session(): TtsSession? = repository.getTtsSession()

    override suspend fun position(storyId: String): TtsStoryPosition? = repository.getTtsStoryPosition(storyId)
}
