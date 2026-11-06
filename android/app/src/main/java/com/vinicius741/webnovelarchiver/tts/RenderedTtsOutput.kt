package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.CoroutineScope

/**
 * Pre-renders sentences to a cache and plays them through [TtsPcmPlayer]. Rendering starts at the
 * listening position and runs ahead of it; going back a little and the rest of the chapter follow,
 * so seeks are usually instant and the chapter's length soon becomes exact.
 */
internal class RenderedTtsOutput(
    private val cache: TtsAudioCache,
    synthesizer: TtsSpeechSynthesizer,
    scope: CoroutineScope,
    private val listener: TtsOutput.Listener,
    private val dispatch: (() -> Unit) -> Unit,
    private val player: TtsPcmPlayer = TtsPcmPlayer(),
) : TtsOutput,
    TtsPlaybackControls by player {
    private val renderer =
        TtsAudioRenderer(
            cache,
            synthesizer,
            scope,
            object : TtsAudioRenderer.Callbacks {
                override fun onRendered(
                    request: TtsRenderRequest,
                    durationMs: Long,
                ) {
                    chapter?.onRendered(request.key, durationMs)
                }

                override fun onRenderFailed(request: TtsRenderRequest) = dispatchIfCurrent { listener.onUnavailable() }

                override fun onPlanFinished() = dispatchIfCurrent { listener.onRenderPlanFinished() }
            },
        )

    @Volatile
    private var chapter: TtsRenderedChapter? = null
    private var voiceKey = ""
    private var aheadRequests: List<TtsRenderRequest> = emptyList()

    @Volatile
    private var generation = 0L

    init {
        player.listener =
            object : TtsPcmPlayer.Listener {
                override fun onItemStarted(item: Int) = dispatchIfCurrent { listener.onItemStarted(item) }

                override fun onEnded() = dispatchIfCurrent { listener.onEnded() }

                override fun onFailed(error: Throwable) = dispatchIfCurrent { listener.onUnavailable() }
            }
    }

    override fun load(request: TtsOutputLoad) {
        generation++
        val sent = generation
        voiceKey = request.voiceKey
        aheadRequests = emptyList()
        val loaded =
            TtsRenderedChapter(request.timeline, cache, voiceKey) {
                val missing = it
                dispatch { if (sent == generation) renderer.request(missing) }
            }
        chapter = loaded
        renderer.setPlan(loaded.planFrom(request.item))
        player.start(loaded, request.item, request.offsetMs, request.rate, request.playWhenReady)
    }

    override fun seek(
        item: Int,
        offsetMs: Long,
    ) {
        chapter?.let { renderer.setPlan(it.planFrom(item) + aheadRequests) }
        player.seek(item, offsetMs)
    }

    override fun stop() {
        generation++
        renderer.stop()
        player.stop()
        chapter = null
    }

    override fun prepareAhead(texts: List<String>) {
        aheadRequests = texts.distinct().map { TtsRenderRequest(cache.keyFor(voiceKey, it), it) }
        renderer.setPlan(aheadRequests)
    }

    override fun release() {
        generation++
        renderer.stop()
        player.release()
        chapter = null
    }

    /** A callback left over from a replaced chapter must not act on the new one. */
    private fun dispatchIfCurrent(block: () -> Unit) {
        val sent = generation
        dispatch { if (sent == generation) block() }
    }
}

/** One chapter's items mapped onto the audio cache. */
private class TtsRenderedChapter(
    private val timeline: TtsTimeline,
    private val cache: TtsAudioCache,
    voiceKey: String,
    private val requestRender: (TtsRenderRequest) -> Unit,
) : TtsPcmFeed {
    private val keys: List<String?> =
        timeline.items.map { item -> (item as? TtsTimelineItem.Speech)?.let { cache.keyFor(voiceKey, it.text) } }
    private val itemsByKey: Map<String, List<Int>> =
        keys.indices.filter { keys[it] != null }.groupBy { keys[it]!! }

    override val itemCount: Int get() = timeline.items.size

    override val phrasePauseMs: Long get() = timeline.phrasePauseMs

    override fun silenceMs(item: Int): Long? = (timeline.items.getOrNull(item) as? TtsTimelineItem.Silence)?.durationMs

    override fun awaitClip(
        item: Int,
        timeoutMs: Long,
    ): TtsPcmClip? {
        val key = keys.getOrNull(item) ?: return null
        val clip = cache.awaitClip(key, timeoutMs)
        if (clip == null) {
            val speech = timeline.items[item] as TtsTimelineItem.Speech
            requestRender(TtsRenderRequest(key, speech.text))
        }
        return clip
    }

    fun onRendered(
        key: String,
        durationMs: Long,
    ) {
        itemsByKey[key]?.forEach { timeline.setKnownDuration(it, durationMs) }
    }

    /** From the given item to the end, then backward from it, so going back a little is ready soon. */
    fun planFrom(item: Int): List<TtsRenderRequest> {
        val indices = timeline.items.indices
        val start = item.coerceIn(indices)
        return ((start..indices.last) + (start - 1 downTo 0))
            .mapNotNull { index ->
                val key = keys[index]
                val speech = timeline.items[index] as? TtsTimelineItem.Speech
                if (key != null && speech != null) TtsRenderRequest(key, speech.text) else null
            }.distinctBy { it.key }
    }
}
