package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.flow.StateFlow

/** [ReaderData] backed by the repository, the narration coordinator and the playback engine. */
internal class RepositoryReaderData(
    private val repository: AppRepository,
    private val narration: NarrationCoordinator,
    private val tts: TtsEngine,
    private val palette: ReaderDocumentPalette,
) : ReaderData {
    override val playback: StateFlow<TtsPlaybackUpdate> get() = tts.playbackState

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun prepare(
        storyId: String,
        chapterId: String,
    ): ReaderPreparation {
        val startedNanos = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val preparation = ReaderDocumentPreparer(repository).prepare(storyId, chapterId, palette)
        if (BuildConfig.DEBUG) {
            val outcome =
                when (preparation) {
                    is ReaderPreparation.Ready -> "ready"
                    ReaderPreparation.Missing -> "missing"
                    is ReaderPreparation.Failed -> "failed"
                }
            PerfInstrumentation.recordReaderPrepareDone(outcome, (System.nanoTime() - startedNanos) / 1_000_000)
        }
        return preparation
    }

    override suspend fun toggleBookmark(
        storyId: String,
        chapterId: String,
    ): Story? = repository.toggleBookmark(storyId, chapterId)

    override suspend fun saveDisplay(display: ReaderDisplay) {
        repository.updateDisplayPreferences { it.copy(readerFontScale = display.fontScale, readerDark = display.pureBlack) }
    }

    override suspend fun listenPosition(storyId: String): TtsStoryPosition? = repository.getTtsStoryPosition(storyId)

    override fun voiceLabel(): String? = runCatching { narration.currentVoice().label }.getOrNull()

    override fun savedRate(): Float = repository.ttsSettings.get().rate
}

/** [ReaderPlayback] through the listen starts and the playback service. */
internal class ServiceReaderPlayback(
    private val host: ScreenHost,
) : ReaderPlayback {
    private val context get() = host.app

    override fun playFromPlace(
        storyId: String,
        chapterId: String,
    ) = host.playIfGenerated(storyId, chapterId) { TtsForegroundService.start(context, storyId, chapterId) }

    override fun continueFrom(
        storyId: String,
        savedChapterId: String,
        readerChapterId: String,
    ) = host.playIfGenerated(storyId, savedChapterId) {
        TtsForegroundService.start(context, storyId, readerChapterId)
        if (host.navigator.current == AppRoute.Reader(storyId, readerChapterId)) host.showReader(storyId, savedChapterId)
    }

    override fun playFromBeginning(
        storyId: String,
        chapterId: String,
    ) = host.playIfGenerated(storyId, chapterId) { TtsForegroundService.startFromChunk(context, storyId, chapterId, 0) }

    override fun resumeSession() = command(TtsForegroundService.ACTION_RESUME_SESSION)

    override fun pause() = command(TtsForegroundService.ACTION_PAUSE)

    override fun skipBack() = command(TtsForegroundService.ACTION_SKIP_BACK)

    override fun setRate(rate: Float) = TtsForegroundService.setRate(context, rate)

    override fun stop() = command(TtsForegroundService.ACTION_STOP)

    private fun command(action: String) = TtsForegroundService.command(context, action)
}
