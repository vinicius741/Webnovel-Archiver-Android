package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

/**
 * The fallback when an engine cannot render to files: the engine speaks one sentence at a time. It
 * follows the same script, but time is only estimated and pause depends on word callbacks, which
 * not every voice sends; without them a resume restarts the sentence.
 */
internal class LiveTtsOutput(
    private val engine: () -> TextToSpeech?,
    scope: CoroutineScope,
    private val listener: TtsOutput.Listener,
    private val dispatch: (() -> Unit) -> Unit,
) : TtsOutput {
    private var timeline: TtsTimeline? = null
    private var rate = 1f
    private var active = false
    private var currentItem = 0
    private var baseCharOffset = 0
    private var spokenChars = 0
    private var currentUtteranceId: String? = null
    private var sequence = 0L
    private val watchdog =
        TtsWatchdog(
            scope = scope,
            onRetry = { utteranceId, _ ->
                dispatch {
                    if (active && utteranceId == currentUtteranceId) {
                        engine()?.stop()
                        currentUtteranceId = null
                        speakCurrent()
                    }
                }
            },
            onStalled = { utteranceId ->
                dispatch {
                    if (active && utteranceId == currentUtteranceId) {
                        active = false
                        listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.Stalled))
                    }
                }
            },
        )

    val progressListener =
        TtsUtteranceProgressListener(
            onUtteranceError = { id, code ->
                dispatch {
                    if (id == currentUtteranceId) {
                        currentUtteranceId = null
                        watchdog.cancel()
                        active = false
                        listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.SynthesisFailed, code = code))
                    }
                }
            },
            onUtteranceDone = { id ->
                dispatch {
                    TtsEngineLogging.utteranceDone(id, currentUtteranceId)
                    if (id == currentUtteranceId) {
                        currentUtteranceId = null
                        watchdog.cancel()
                        if (active) advance()
                    }
                }
            },
            onWordStart = { id, start ->
                dispatch { if (id == currentUtteranceId) spokenChars = start }
            },
        )

    override fun load(request: TtsOutputLoad) {
        timeline = request.timeline
        rate = request.rate
        watchdog.reset()
        moveTo(request.item, request.offsetMs)
        active = request.playWhenReady
        if (active) speakCurrent()
    }

    override fun play() {
        if (active || timeline == null) return
        active = true
        // Carry on from the last word the engine reported.
        baseCharOffset += spokenChars
        spokenChars = 0
        speakCurrent()
    }

    override fun pause() {
        active = false
        watchdog.cancel()
        currentUtteranceId = null
        engine()?.stop()
    }

    override fun seek(
        item: Int,
        offsetMs: Long,
    ) {
        val wasActive = active
        pause()
        moveTo(item, offsetMs)
        active = wasActive
        if (active) speakCurrent()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        engine()?.setSpeechRate(rate)
        if (active) {
            pause()
            play()
        }
    }

    override fun position(): TtsTimelinePoint {
        val current = timeline ?: return TtsTimelinePoint(0, 0L)
        val speech = current.items.getOrNull(currentItem) as? TtsTimelineItem.Speech
        val heard = baseCharOffset + spokenChars
        val offset = if (speech == null || speech.text.isEmpty()) 0L else current.durationMs(currentItem) * heard / speech.text.length
        return TtsTimelinePoint(currentItem, offset)
    }

    override fun stop() {
        pause()
        timeline = null
    }

    private fun moveTo(
        item: Int,
        offsetMs: Long,
    ) {
        currentItem = item
        spokenChars = 0
        baseCharOffset = timeline?.let { TtsNarrationPlanning.charOffsetAt(it, item, offsetMs) } ?: 0
    }

    private fun advance() {
        val current = timeline ?: return
        if (currentItem >= current.items.lastIndex) {
            active = false
            listener.onEnded()
            return
        }
        currentItem++
        baseCharOffset = 0
        spokenChars = 0
        speakCurrent()
    }

    private fun speakCurrent() {
        val tts = engine()
        val current = timeline
        if (tts == null || current == null) {
            active = false
            listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
            return
        }
        val id = "chapter_item_${currentItem}_${sequence++}"
        val item = current.items.getOrNull(currentItem)
        val result =
            when (item) {
                is TtsTimelineItem.Silence -> {
                    // The voice already ends each sentence with a pause of its own, shortened by its speed.
                    val extraMs = item.durationMs - (VOICE_TAIL_MS / rate).toLong()
                    if (extraMs < MIN_SILENCE_MS) {
                        advance()
                        return
                    }
                    tts.playSilentUtterance(extraMs, TextToSpeech.QUEUE_FLUSH, id)
                }
                is TtsTimelineItem.Speech -> {
                    listener.onItemStarted(currentItem)
                    val text = item.text.substring(baseCharOffset.coerceIn(0, item.text.length))
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id).also { speakResult ->
                        TtsEngineLogging.speak(currentItem, current.items.size, id, text, speakResult)
                    }
                }
                null -> {
                    listener.onEnded()
                    return
                }
            }
        if (result == TextToSpeech.ERROR) {
            Timber.w("TTS speak returned ERROR")
            active = false
            listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.SpeakFailed))
            return
        }
        currentUtteranceId = id
        if (item is TtsTimelineItem.Speech) {
            if (currentItem != watchdog.chunkIndex) {
                watchdog.chunkIndex = currentItem
                watchdog.retryCount = 0
            }
            watchdog.schedule(id, item.text, rate, currentItem)
        }
    }

    private companion object {
        const val VOICE_TAIL_MS = 300L
        const val MIN_SILENCE_MS = 50L
    }
}
