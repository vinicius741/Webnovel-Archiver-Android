package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a chapter from its generated pack: every sentence is already on disk, so nothing is
 * synthesized and no speech engine or model is loaded. Durations come from the pack's index, so the
 * chapter's length is exact from the start. A missing clip reports [TtsPlaybackErrorKind.AudioNotGenerated]
 * instead of falling back to speech.
 */
internal class PackTtsOutput(
    private val store: NarrationStore,
    private val listener: TtsOutput.Listener,
    private val dispatch: (() -> Unit) -> Unit,
    private val player: TtsPcmPlayer = TtsPcmPlayer(),
) : TtsOutput,
    TtsPlaybackControls by player {
    private var pack: NarrationPack? = null

    @Volatile
    private var generation = 0L

    init {
        player.listener =
            object : TtsPcmPlayer.Listener {
                override fun onItemStarted(item: Int) = dispatchIfCurrent { listener.onItemStarted(item) }

                override fun onEnded() = dispatchIfCurrent { listener.onEnded() }

                override fun onFailed(error: Throwable) =
                    dispatchIfCurrent { listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.AudioNotGenerated, detail = error.message)) }
            }
    }

    override fun load(request: TtsOutputLoad) {
        val loaded = requireNotNull(request.pack) { "A pack output needs a pack" }
        generation++
        val sent = generation
        releasePack()
        store.retain(loaded)
        pack = loaded
        applyDurations(request.timeline, loaded)
        val reportedMissing = AtomicBoolean(false)
        val feed =
            PackFeed(request.timeline, loaded, store) {
                if (reportedMissing.compareAndSet(false, true)) {
                    dispatch {
                        if (sent == generation) {
                            listener.onError(TtsPlaybackError(TtsPlaybackErrorKind.AudioNotGenerated, detail = "clip missing"))
                        }
                    }
                }
            }
        player.start(feed, request.item, request.offsetMs, request.rate, request.playWhenReady)
    }

    override fun seek(
        item: Int,
        offsetMs: Long,
    ) = player.seek(item, offsetMs)

    override fun stop() {
        generation++
        player.stop()
        releasePack()
    }

    override fun release() {
        generation++
        player.release()
        releasePack()
    }

    private fun releasePack() {
        pack?.let(store::release)
        pack = null
    }

    private fun applyDurations(
        timeline: TtsTimeline,
        pack: NarrationPack,
    ) {
        timeline.items.forEachIndexed { index, item ->
            val speech = item as? TtsTimelineItem.Speech ?: return@forEachIndexed
            val duration =
                if (speech.isTitle) {
                    pack.index.titleDurationMs ?: 0L
                } else {
                    pack.index.sentenceDurationsMs.getOrNull(speech.chunkIndex)
                }
            duration?.let { timeline.setKnownDuration(index, it) }
        }
    }

    /** A callback left over from a replaced chapter must not act on the new one. */
    private fun dispatchIfCurrent(block: () -> Unit) {
        val sent = generation
        dispatch { if (sent == generation) block() }
    }
}

/** A chapter's timeline items mapped onto its pack's clips. */
private class PackFeed(
    private val timeline: TtsTimeline,
    private val pack: NarrationPack,
    private val store: NarrationStore,
    private val onMissing: () -> Unit,
) : TtsPcmFeed {
    override val itemCount: Int get() = timeline.items.size

    override val phrasePauseMs: Long get() = timeline.phrasePauseMs

    override fun silenceMs(item: Int): Long? = (timeline.items.getOrNull(item) as? TtsTimelineItem.Silence)?.durationMs

    override fun awaitClip(
        item: Int,
        timeoutMs: Long,
    ): TtsPcmClip? {
        val speech = timeline.items.getOrNull(item) as? TtsTimelineItem.Speech ?: return null
        val file =
            when {
                // A title the pack never generated is skipped rather than spoken by anything else.
                speech.isTitle && pack.recipe.title == null -> return TtsPcmClip(pack.index.sampleRate, ByteArray(0))
                speech.isTitle -> store.titleFile(pack.dir)
                speech.chunkIndex in pack.recipe.sentences.indices -> store.clipFile(pack.dir, speech.chunkIndex)
                else -> null
            }
        val clip = file?.let(store::readClip)
        if (clip == null) {
            onMissing()
            Thread.sleep(timeoutMs)
        }
        return clip
    }
}
