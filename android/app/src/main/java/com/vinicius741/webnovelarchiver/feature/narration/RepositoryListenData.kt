package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.feature.player.ContinuePlace
import com.vinicius741.webnovelarchiver.feature.player.PlayerData
import com.vinicius741.webnovelarchiver.feature.player.RepositoryPlayerData
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import kotlinx.coroutines.flow.StateFlow

/** [ListenData] over the narration coordinator; playback and the playing story come from [player]. */
internal class RepositoryListenData(
    private val narration: NarrationCoordinator,
    private val player: RepositoryPlayerData,
    private val repository: AppRepository,
) : ListenData,
    PlayerData by player {
    override val queue: StateFlow<NarrationQueueDocument> get() = narration.queueState
    override val progress: StateFlow<NarrationProgress?> get() = narration.progress
    override val generationRunning: StateFlow<Boolean> get() = narration.generationRunning
    override val packsRevision: StateFlow<Long> get() = narration.packsRevision
    override val compression: StateFlow<NarrationCompressionStatus> get() = narration.compression

    override fun chapterTitle(
        storyId: String,
        chapterId: String,
    ): String = narration.chapterTitle(storyId, chapterId)

    override fun generatedAudio(): GeneratedAudio {
        val detached = narration.detachedPacks()
        val detachedKeys = detached.values.flatten().mapTo(HashSet()) { it.storyId to it.chapterId }
        val attached =
            narration.store
                .allPacks()
                .filter { (it.storyId to it.chapterId) !in detachedKeys }
                .groupBy { it.storyId }
        return GeneratedAudio(attached, detached)
    }

    override fun usedBytes(): Long = narration.store.totalBytes()

    override fun freeBytes(): Long = narration.freeBytes()

    override suspend fun listenedAt(): Map<String, Long> = repository.getTtsStoryPositions().associate { it.storyId to it.updatedAt }

    override fun voiceCanUseComputers(): Boolean = KokoroVoices.model(narration.currentVoice().engine) != null

    override fun lastContinuePlace(): ContinuePlace? = player.lastContinuePlace()

    override fun pause() = narration.pause()

    override fun resume() = narration.resume()

    override fun retry(jobId: String) = narration.retry(jobId)

    override fun retryAll() = narration.retryAll()

    override fun clearFailed() = narration.clearFailed()

    override fun clearQueue() = narration.clearQueue()

    override fun remove(jobIds: Set<String>) = narration.remove(jobIds)

    override fun moveToTop(jobId: String) = narration.moveToTop(jobId)

    override fun moveStoryToTop(storyId: String) = narration.moveStoryToTop(storyId)

    override suspend fun deleteAudio(
        storyId: String,
        chapterIds: List<String>,
    ) {
        narration.deleteAudio(storyId, chapterIds)
    }

    override fun setGenerateOn(
        serverIds: List<String>,
        phoneFallback: Boolean,
    ) = narration.setGenerateOn(serverIds, phoneFallback)
}
