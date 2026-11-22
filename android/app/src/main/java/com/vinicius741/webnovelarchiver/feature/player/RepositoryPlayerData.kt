package com.vinicius741.webnovelarchiver.feature.player

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** [PlayerData] backed by the repository, the narration packs and the playback engine. */
internal class RepositoryPlayerData(
    private val repository: AppRepository,
    private val narration: NarrationCoordinator,
    private val tts: TtsEngine,
) : PlayerData {
    override val playback: StateFlow<TtsPlaybackUpdate> get() = tts.playbackState

    override fun story(id: String): Story? = repository.story(id)

    override fun packVoice(
        storyId: String,
        chapterId: String,
    ): String? =
        narration.store
            .pack(storyId, chapterId)
            ?.recipe
            ?.voice
            ?.label

    override suspend fun chapterSentences(
        storyId: String,
        chapterId: String,
    ): List<String> {
        val story = repository.story(storyId) ?: return emptyList()
        val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return emptyList()
        val html =
            withContext(Dispatchers.IO) {
                runCatching { repository.readChapter(chapter) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            } ?: chapter.content
        if (html.isNullOrBlank()) return emptyList()
        val rules = repository.regexRules.get()
        return withContext(Dispatchers.Default) {
            runCatching { TtsTextPreparation.prepareTtsChunks(html, rules) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
        }
    }

    override suspend fun continuePlace(): ContinuePlace? {
        val positions =
            runCatching { repository.getTtsStoryPositions() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
        val place =
            TtsContinueListening.target(positions) { repository.story(it) }?.let { target ->
                val sentence =
                    positions
                        .firstOrNull { it.storyId == target.story.id }
                        ?.takeIf { !it.caughtUp && it.chapterId == target.chapter.id }
                        ?.let { "sentence ${it.currentChunkIndex + 1}" }
                ContinuePlace(target, sentence)
            }
        lastContinuePlace = place
        return place
    }

    /** The place [continuePlace] found last, so a new screen can show it before looking again. */
    fun lastContinuePlace(): ContinuePlace? = lastContinuePlace

    private companion object {
        /** Process-wide, like the places it summarizes; main-thread reads tolerate a stale value. */
        @Volatile
        var lastContinuePlace: ContinuePlace? = null
    }
}
