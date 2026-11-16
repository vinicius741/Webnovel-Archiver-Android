package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationSampleRenderer
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerProblem
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInEngines
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Plays typed text through the same pipeline as a chapter, with settings that need not be saved, so
 * what is heard here is what a chapter will sound like. It has its own speech engine and takes audio
 * focus like any other app, which pauses a playing session and leaves that session's place alone.
 *
 * A built-in model that is not on this phone is never replaced by another voice here: the sample is
 * generated on the chosen computers first (see [NarrationSampleRenderer]) and played once every
 * sentence is back. Callbacks arrive on the main thread; call everything from there too.
 */
internal class TtsSamplePreview(
    context: Context,
    private val builtIns: BuiltInEngines,
    private val onStateChanged: (TtsSampleState) -> Unit,
    private val onFailed: (TtsSampleFailure) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMutex = Mutex()
    private var latestRequest = 0L
    private var pending: Sample? = null
    private var renderJob: Job? = null
    private val speech =
        TtsSpeechEngineHost(
            context = context,
            scope = scope,
            stateMutex = stateMutex,
            listeners = TtsEventListeners(),
            builtIns = builtIns,
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
                        // The built-in model has no live mode to fall back to.
                        if (speech.isBuiltin) return endLocked(TtsSampleFailure.Engine)
                        outputs.switchToLive()
                        loadLocked()
                    }

                    override fun onError(error: TtsPlaybackError) = endLocked(TtsSampleFailure.Engine)

                    override fun onRenderPlanFinished() = Unit
                },
            dispatch = { block -> scope.launch { stateMutex.withLock { block() } } },
        )

    /**
     * Plays a sample generated on the computers; nothing is synthesized on this phone. Each sample
     * gets its own player, so a late callback from a replaced one can only reach its own listener.
     */
    private var generatedPlayer: TtsPcmPlayer? = null

    /** Tells a finished generated sample's late callback apart from the current one's. */
    private var generatedRun = 0L

    private val focus =
        TtsAudioFocusManager(
            context,
            object : TtsAudioFocusManager.Callbacks {
                override fun onFocusGained(afterCall: Boolean) = Unit

                override fun onTransientFocusLoss() = stop()

                override fun onPermanentFocusLoss() = stop()
            },
        )

    /**
     * Starts the sample from the top, replacing any sample already playing. A built-in model that is
     * not on this phone generates on [computers] first.
     */
    fun play(
        text: String,
        settings: TtsSettings,
        computers: List<NarrationServer> = emptyList(),
    ) {
        val request = ++latestRequest
        renderJob?.cancel()
        scope.launch {
            val items = withContext(Dispatchers.Default) { sampleItems(text, settings) }
            stateMutex.withLock {
                if (request != latestRequest) return@withLock
                stopOutputsLocked()
                if (items.isEmpty()) return@withLock endLocked(TtsSampleFailure.NothingToRead)
                val model = KokoroVoices.model(settings.enginePackage)
                val onPhone = model == null || (builtIns.isInstalled(model) && KokoroModelFiles.isDeviceSupported(model))
                when (val place = TtsSamplePlanning.place(settings, onPhone, computers)) {
                    TtsSamplePlace.Phone -> playOnPhoneLocked(items, settings)
                    is TtsSamplePlace.Computers -> generateLocked(request, items, settings, place)
                    is TtsSamplePlace.Unavailable -> endLocked(place.failure)
                }
            }
        }
    }

    fun stop() {
        latestRequest++
        renderJob?.cancel()
        scope.launch { stateMutex.withLock { endLocked() } }
    }

    /** Frees the speech engine; this instance cannot be used again. */
    fun release() {
        latestRequest++
        pending = null
        scope.cancel()
        outputs.release()
        generatedPlayer?.release()
        generatedPlayer = null
        speech.shutdown()
        focus.abandon()
    }

    private fun playOnPhoneLocked(
        items: List<TtsTimelineItem>,
        settings: TtsSettings,
    ) {
        if (!focus.request()) return endLocked(TtsSampleFailure.AudioBusy)
        pending = Sample(TtsTimeline(items, settings.phrasePauseMs), settings)
        onStateChanged(TtsSampleState.Playing)
        speech.selectEngine(settings)
        speech.ensureEngine()
        if (speech.initialized) loadLocked() else speech.watchInit()
    }

    private fun generateLocked(
        request: Long,
        items: List<TtsTimelineItem>,
        settings: TtsSettings,
        place: TtsSamplePlace.Computers,
    ) {
        val speeches = items.withIndex().filter { it.value is TtsTimelineItem.Speech }
        val texts = speeches.map { (it.value as TtsTimelineItem.Speech).text }
        // Only pauses (a lone scene break): nothing to send anywhere.
        if (texts.isEmpty()) return endLocked(TtsSampleFailure.NothingToRead)
        onStateChanged(TtsSampleState.Generating(0, texts.size, TtsSamplePlanning.where(place.servers.map { it.name })))
        renderJob =
            scope.launch {
                var failure = ""
                val clips =
                    try {
                        withContext(Dispatchers.IO) {
                            NarrationSampleRenderer.forServers(place.servers, place.speaker).render(texts) { done, generatingOn ->
                                // Called in order under the renderer's lock, and posted to the main thread in that order.
                                scope.launch {
                                    if (request == latestRequest) {
                                        onStateChanged(TtsSampleState.Generating(done, texts.size, TtsSamplePlanning.where(generatingOn)))
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (problem: NarrationServerProblem) {
                        failure = problem.message.orEmpty()
                        null
                    } catch (
                        @Suppress("TooGenericExceptionCaught") unexpected: Exception,
                    ) {
                        // Anything else from the network is still the computers failing, never a crash.
                        Timber.w(unexpected, "Sample generation failed")
                        null
                    }
                stateMutex.withLock {
                    if (request != latestRequest) return@withLock
                    if (clips == null) return@withLock endLocked(TtsSampleFailure.Computers(failure))
                    if (!focus.request()) return@withLock endLocked(TtsSampleFailure.AudioBusy)
                    val byItem = speeches.map { it.index }.zip(clips).toMap()
                    val run = ++generatedRun
                    val player =
                        TtsPcmPlayer().apply {
                            listener =
                                object : TtsPcmPlayer.Listener {
                                    override fun onItemStarted(item: Int) = Unit

                                    override fun onEnded() = onGeneratedEvent(run, null)

                                    override fun onFailed(error: Throwable) = onGeneratedEvent(run, TtsSampleFailure.Engine)
                                }
                        }
                    generatedPlayer = player
                    player.start(GeneratedSampleFeed(items, byItem, settings.phrasePauseMs), 0, 0L, settings.rate, true)
                    onStateChanged(TtsSampleState.Playing)
                }
            }
    }

    /** Generated sample [run] ended or failed; ignored once another sample has replaced it. */
    private fun onGeneratedEvent(
        run: Long,
        failure: TtsSampleFailure?,
    ) {
        scope.launch { stateMutex.withLock { if (run == generatedRun) endLocked(failure) } }
    }

    private fun loadLocked() {
        val sample = pending ?: return
        speech.ensureEngine()
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
                voiceKey = speech.voiceKey(sample.settings),
            ),
        )
        outputs.loaded = true
    }

    private fun stopOutputsLocked() {
        outputs.stopActive()
        generatedRun++
        generatedPlayer?.release()
        generatedPlayer = null
    }

    private fun endLocked(failure: TtsSampleFailure? = null) {
        pending = null
        speech.cancelInitWatchdog()
        stopOutputsLocked()
        focus.abandon()
        onStateChanged(TtsSampleState.Idle)
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

/** A sample whose sentences were all generated before it started. */
private class GeneratedSampleFeed(
    private val items: List<TtsTimelineItem>,
    private val clips: Map<Int, TtsPcmClip>,
    override val phrasePauseMs: Long,
) : TtsPcmFeed {
    override val itemCount: Int get() = items.size

    override fun silenceMs(item: Int): Long? = (items.getOrNull(item) as? TtsTimelineItem.Silence)?.durationMs

    override fun awaitClip(
        item: Int,
        timeoutMs: Long,
    ): TtsPcmClip? = clips[item]
}

/** What the sample card shows. */
internal sealed interface TtsSampleState {
    data object Idle : TtsSampleState

    /** Waiting on the computers: [done] of [total] sentences are back from [where]. */
    data class Generating(
        val done: Int,
        val total: Int,
        val where: String,
    ) : TtsSampleState

    data object Playing : TtsSampleState
}

internal sealed interface TtsSampleFailure {
    data object NothingToRead : TtsSampleFailure

    data object AudioBusy : TtsSampleFailure

    data object Engine : TtsSampleFailure

    /** The chosen voice's built-in [model] is not on this phone and no computer is chosen to generate it. */
    data class NoComputer(
        val model: String,
    ) : TtsSampleFailure

    /** The computers could not generate the sample; [message] says why. */
    data class Computers(
        val message: String,
    ) : TtsSampleFailure
}
