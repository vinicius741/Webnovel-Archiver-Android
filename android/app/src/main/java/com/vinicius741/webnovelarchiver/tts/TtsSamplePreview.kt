package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Plays typed text through the same pipeline as a chapter, with settings that need not be saved, so
 * what is heard here is what a chapter will sound like. It has its own speech engine and takes audio
 * focus like any other app, which pauses a playing session and leaves that session's place alone.
 * Callbacks arrive on the main thread; call everything from there too.
 */
internal class TtsSamplePreview(
    context: Context,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onFailed: (TtsSampleFailure) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMutex = Mutex()
    private var latestRequest = 0L
    private var pending: Sample? = null
    private val speech =
        TtsSpeechEngineHost(
            context = context,
            scope = scope,
            stateMutex = stateMutex,
            listeners = TtsEventListeners(),
            onReady = ::loadLocked,
            onFailed = { endLocked(TtsSampleFailure.Engine) },
        )
    private val outputs: TtsOutputs =
        TtsOutputs(
            context = context,
            host = speech,
            scope = scope,
            listener =
                object : TtsOutput.Listener {
                    override fun onItemStarted(item: Int) = Unit

                    override fun onEnded() = endLocked()

                    override fun onUnavailable() {
                        outputs.switchToLive()
                        loadLocked()
                    }

                    override fun onError(error: TtsPlaybackError) = endLocked(TtsSampleFailure.Engine)

                    override fun onRenderPlanFinished() = Unit
                },
            dispatch = { block -> scope.launch { stateMutex.withLock { block() } } },
        )
    private val focus =
        TtsAudioFocusManager(
            context,
            object : TtsAudioFocusManager.Callbacks {
                override fun onFocusGained() = Unit

                override fun onTransientFocusLoss() = stop()

                override fun onPermanentFocusLoss() = stop()
            },
        )

    /** Starts the sample from the top, replacing any sample already playing. */
    fun play(
        text: String,
        settings: TtsSettings,
    ) {
        val request = ++latestRequest
        scope.launch {
            val items = withContext(Dispatchers.Default) { sampleItems(text, settings) }
            stateMutex.withLock {
                if (request != latestRequest) return@withLock
                outputs.stopActive()
                when {
                    items.isEmpty() -> endLocked(TtsSampleFailure.NothingToRead)
                    !focus.request() -> endLocked(TtsSampleFailure.AudioBusy)
                    else -> {
                        pending = Sample(TtsTimeline(items, settings.phrasePauseMs), settings)
                        onPlayingChanged(true)
                        speech.ensureEngine()
                        if (speech.initialized) loadLocked() else speech.watchInit()
                    }
                }
            }
        }
    }

    fun stop() {
        latestRequest++
        scope.launch { stateMutex.withLock { endLocked() } }
    }

    /** Frees the speech engine; this instance cannot be used again. */
    fun release() {
        latestRequest++
        pending = null
        scope.cancel()
        outputs.release()
        speech.shutdown()
        focus.abandon()
    }

    private fun loadLocked() {
        val sample = pending ?: return
        val engine = speech.ensureEngine()
        val synthesisRate = if (outputs.useLive) sample.settings.rate else 1f
        val applied = speech.applySettings(sample.settings, synthesisRate) { endLocked(TtsSampleFailure.Engine) }
        if (!applied) return
        speech.cancelInitWatchdog()
        val target = outputs.prepare()
        target.load(
            TtsOutputLoad(
                timeline = sample.timeline,
                item = 0,
                offsetMs = 0L,
                rate = sample.settings.rate,
                playWhenReady = true,
                voiceKey = ttsVoiceKey(engine, sample.settings),
            ),
        )
        outputs.loaded = true
    }

    private fun endLocked(failure: TtsSampleFailure? = null) {
        pending = null
        speech.cancelInitWatchdog()
        outputs.stopActive()
        focus.abandon()
        onPlayingChanged(false)
        failure?.let(onFailed)
    }

    private fun sampleItems(
        text: String,
        settings: TtsSettings,
    ): List<TtsTimelineItem> {
        val narration = TtsTextPreparation.prepareTtsNarration(TtsNarrationPlanning.sampleHtml(text), emptyList())
        // The pause that closes a chapter has nothing after it to judge it by.
        return TtsNarrationPlanning.items(narration, settings = settings).dropLast(1)
    }

    private class Sample(
        val timeline: TtsTimeline,
        val settings: TtsSettings,
    )
}

internal enum class TtsSampleFailure {
    NothingToRead,
    AudioBusy,
    Engine,
}
