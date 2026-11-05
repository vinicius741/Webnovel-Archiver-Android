package com.vinicius741.webnovelarchiver.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Process
import android.os.SystemClock
import timber.log.Timber

/** The audio a [TtsPcmPlayer] plays, addressed by timeline item. */
internal interface TtsPcmFeed {
    val itemCount: Int

    /** Extra real time to hold at each gap inside a sentence. */
    val phrasePauseMs: Long get() = 0L

    /** Non-null when the item is a pause of this length. */
    fun silenceMs(item: Int): Long?

    /** The item's audio, waiting up to [timeoutMs]; null means it is not rendered yet. */
    fun awaitClip(
        item: Int,
        timeoutMs: Long,
    ): TtsPcmClip?
}

/**
 * Plays a chapter as one continuous stream on a single [AudioTrack], so pauses are sample-exact and
 * the position comes from the audio hardware. The track's own time stretching handles speed with
 * pitch preserved, which needs no re-rendering. Pauses are written longer as speed rises, so they
 * last the same real time at any speed. Seeking replaces the track, which resets its counter.
 */
internal class TtsPcmPlayer : TtsPlaybackControls {
    interface Listener {
        fun onItemStarted(item: Int)

        fun onEnded()

        fun onFailed(error: Throwable)
    }

    /** Set before [start]; callbacks arrive on the player's own thread. */
    @Volatile
    var listener: Listener? = null
    private val lock = Object()
    private var feed: TtsPcmFeed? = null
    private var generation = 0L
    private var requestedItem = 0
    private var requestedOffsetMs = 0L
    private var playWhenReady = false
    private var speed = 1f
    private var volume = 1f
    private var released = false
    private var thread: Thread? = null

    @Volatile
    private var position = TtsTimelinePoint(0, 0L)

    fun start(
        feed: TtsPcmFeed,
        item: Int,
        offsetMs: Long,
        speed: Float,
        playWhenReady: Boolean,
    ) {
        synchronized(lock) {
            this.feed = feed
            this.speed = speed
            this.playWhenReady = playWhenReady
            seek(item, offsetMs)
            if (thread == null) {
                thread = Thread(::runLoop, "WNA-TtsPcmPlayer").apply { isDaemon = true }.also { it.start() }
            }
        }
    }

    fun seek(
        item: Int,
        offsetMs: Long,
    ) {
        synchronized(lock) {
            requestedItem = item
            requestedOffsetMs = offsetMs
            position = TtsTimelinePoint(item, offsetMs)
            generation++
            lock.notifyAll()
        }
    }

    override fun play() {
        synchronized(lock) {
            playWhenReady = true
            lock.notifyAll()
        }
    }

    override fun pause() {
        synchronized(lock) {
            playWhenReady = false
            lock.notifyAll()
        }
    }

    override fun setRate(rate: Float) {
        synchronized(lock) {
            speed = rate
            lock.notifyAll()
        }
    }

    override fun setVolume(volume: Float) {
        synchronized(lock) {
            this.volume = volume
            lock.notifyAll()
        }
    }

    override fun position(): TtsTimelinePoint = position

    /** Drops the current audio; the player can be started again. */
    fun stop() {
        synchronized(lock) {
            feed = null
            playWhenReady = false
            generation++
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            feed = null
            generation++
            lock.notifyAll()
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (true) {
            val writer =
                synchronized(lock) {
                    while (!released && feed == null) lock.wait()
                    if (released) return
                    StreamWriter(generation, feed!!, requestedItem, requestedOffsetMs)
                }
            runCatching { writer.run() }
                .onFailure { error ->
                    Timber.w(error, "TTS audio playback failed")
                    val current = synchronized(lock) { writer.generation == generation }
                    if (current) listener?.onFailed(error)
                    synchronized(lock) { if (writer.generation == generation) lock.wait() }
                }
            writer.closeTrack()
        }
    }

    /** Everything tied to one continuous stretch of audio; a seek starts a new writer. */
    private inner class StreamWriter(
        val generation: Long,
        private val feed: TtsPcmFeed,
        startItem: Int,
        private val startOffsetMs: Long,
    ) {
        private var track: AudioTrack? = null
        private var sampleRate = 0
        private var nextItem = startItem
        private var isFirstItem = true
        private val queue = ArrayDeque<ByteArray>()
        private var pendingOffset = 0
        private var queuedFrames = 0L
        private var writtenFrames = 0L
        private var writeSpeed = 1f
        private var contentEndFrames = -1L
        private var paddingLeft = 0
        private var endNotified = false
        private var lastHead = 0L
        private var lastHeadChangeAt = SystemClock.elapsedRealtime()
        private var lastReportedItem = -1
        private val segments = ArrayList<Segment>()
        private var appliedPlaying = false
        private var appliedSpeed = 0f
        private var appliedVolume = -1f

        fun run() {
            while (true) {
                val shouldPlay: Boolean
                val wantedSpeed: Float
                val wantedVolume: Float
                synchronized(lock) {
                    if (released || generation != this@TtsPcmPlayer.generation) return
                    shouldPlay = playWhenReady
                    wantedSpeed = speed
                    wantedVolume = volume
                }
                writeSpeed = wantedSpeed
                val moved = pump()
                applyTrackState(shouldPlay, wantedSpeed, wantedVolume)
                reportPosition(shouldPlay)
                if (!moved) {
                    // A long pause must not keep waking the CPU; any command interrupts this wait.
                    // The counter settles just after a pause, so keep watching until it has.
                    val settled = SystemClock.elapsedRealtime() - lastHeadChangeAt > SETTLE_MS
                    val waitMs = if ((shouldPlay && !endNotified) || !settled) PLAYING_POLL_MS else PAUSED_POLL_MS
                    synchronized(lock) {
                        if (generation == this@TtsPcmPlayer.generation && !released) lock.wait(waitMs)
                    }
                }
            }
        }

        /** Advances the writing side; returns whether any audio moved. */
        private fun pump(): Boolean {
            if (queue.isEmpty() && contentEndFrames < 0L) loadNextItem()
            queue.firstOrNull()?.let { return writePending(it) }
            if (contentEndFrames >= 0L && paddingLeft > 0) {
                val active = track ?: return false
                val silence = ByteArray(minOf(paddingLeft * BYTES_PER_FRAME, WRITE_BLOCK_BYTES))
                val wrote = active.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                check(wrote >= 0) { "AudioTrack write failed: $wrote" }
                paddingLeft -= wrote / BYTES_PER_FRAME
                return wrote > 0
            }
            return false
        }

        private fun loadNextItem() {
            if (nextItem >= feed.itemCount) {
                contentEndFrames = writtenFrames
                // Short content may never reach the track's start threshold, and the tail must play out.
                paddingLeft = if (sampleRate > 0) TtsPcmPlanning.msToFrames(TAIL_PADDING_MS, sampleRate).toInt() else 0
                return
            }
            val silence = feed.silenceMs(nextItem)
            if (silence != null) {
                // A stream resuming on a pause simply starts at the next sentence.
                if (sampleRate > 0) enqueue(nextItem, 0L, silenceBytes(silence, writeSpeed, sampleRate), 1f / writeSpeed)
                nextItem++
                isFirstItem = false
                return
            }
            val clip = feed.awaitClip(nextItem, CLIP_WAIT_MS) ?: return
            if (clip.pcm.isEmpty()) {
                nextItem++
                isFirstItem = false
                return
            }
            if (sampleRate == 0) {
                sampleRate = clip.sampleRate
                track = createSpeechTrack(sampleRate)
            }
            check(clip.sampleRate == sampleRate) { "Voice changed sample rate mid-chapter" }
            val offsetFrames =
                if (isFirstItem) {
                    TtsPcmPlanning.msToFrames(startOffsetMs, sampleRate).coerceIn(0L, clip.frames.toLong())
                } else {
                    0L
                }
            enqueueSpeech(clip, offsetFrames.toInt())
            nextItem++
            isFirstItem = false
        }

        /** Queues the clip from [startFrame], holding the position still through each phrase pause. */
        private fun enqueueSpeech(
            clip: TtsPcmClip,
            startFrame: Int,
        ) {
            val phrasePauseMs = feed.phrasePauseMs
            var from = startFrame
            if (phrasePauseMs > 0L) {
                TtsPcmPlanning.phraseBreaks(clip).filter { it > from }.forEach { gap ->
                    enqueue(nextItem, from.toLong(), clip.pcm.copyOfRange(from * BYTES_PER_FRAME, gap * BYTES_PER_FRAME), 1f)
                    enqueue(nextItem, gap.toLong(), silenceBytes(phrasePauseMs, writeSpeed, sampleRate), 0f)
                    from = gap
                }
            }
            enqueue(nextItem, from.toLong(), clip.pcm.copyOfRange(from * BYTES_PER_FRAME, clip.pcm.size), 1f)
        }

        private fun enqueue(
            item: Int,
            offsetFrames: Long,
            audio: ByteArray,
            contentPerFrame: Float,
        ) {
            if (audio.isEmpty()) return
            segments += Segment(queuedFrames, item, offsetFrames, contentPerFrame)
            queue.addLast(audio)
            queuedFrames += audio.size / BYTES_PER_FRAME
        }

        private fun writePending(bytes: ByteArray): Boolean {
            val active = track ?: return false
            val length = minOf(bytes.size - pendingOffset, WRITE_BLOCK_BYTES)
            val wrote = active.write(bytes, pendingOffset, length, AudioTrack.WRITE_NON_BLOCKING)
            check(wrote >= 0) { "AudioTrack write failed: $wrote" }
            pendingOffset += wrote
            writtenFrames += wrote / BYTES_PER_FRAME
            if (pendingOffset >= bytes.size) {
                queue.removeFirst()
                pendingOffset = 0
            }
            return wrote > 0
        }

        private fun applyTrackState(
            shouldPlay: Boolean,
            wantedSpeed: Float,
            wantedVolume: Float,
        ) {
            val active = track ?: return
            if (wantedSpeed != appliedSpeed) {
                runCatching { active.playbackParams = PlaybackParams().allowDefaults().setSpeed(wantedSpeed).setPitch(1f) }
                    .onFailure { Timber.w(it, "TTS playback speed %s rejected", wantedSpeed) }
                appliedSpeed = wantedSpeed
            }
            if (wantedVolume != appliedVolume) {
                active.setVolume(wantedVolume)
                appliedVolume = wantedVolume
            }
            if (shouldPlay != appliedPlaying) {
                if (shouldPlay) active.play() else active.pause()
                appliedPlaying = shouldPlay
                lastHeadChangeAt = SystemClock.elapsedRealtime()
            }
        }

        private fun reportPosition(playing: Boolean) {
            val active = track
            if (active == null) {
                // Nothing to play from here on, for example a seek to the very end.
                if (contentEndFrames >= 0L) notifyEndedOnce()
                return
            }
            val head = active.playbackHeadPosition.toLong() and UINT32_MASK
            val now = SystemClock.elapsedRealtime()
            if (head != lastHead) {
                lastHead = head
                lastHeadChangeAt = now
            }
            val segment = segments.lastOrNull { it.startFrame <= head }
            if (segment != null) {
                val frameInItem = segment.offsetFrames + ((head - segment.startFrame) * segment.contentPerFrame).toLong()
                position = TtsTimelinePoint(segment.item, TtsPcmPlanning.framesToMs(frameInItem, sampleRate))
                if (segment.item != lastReportedItem) {
                    lastReportedItem = segment.item
                    listener?.onItemStarted(segment.item)
                }
            }
            val drained = contentEndFrames >= 0L && (head >= contentEndFrames || (playing && now - lastHeadChangeAt > STALL_LIMIT_MS))
            if (drained) notifyEndedOnce()
        }

        private fun notifyEndedOnce() {
            if (endNotified) return
            endNotified = true
            listener?.onEnded()
        }

        fun closeTrack() {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.release()
                }
            }
            track = null
        }
    }

    private class Segment(
        val startFrame: Long,
        val item: Int,
        val offsetFrames: Long,
        /** How far the item's own time moves per frame played: 0 while a phrase pause holds it. */
        val contentPerFrame: Float,
    )

    private companion object {
        const val PLAYING_POLL_MS = 20L
        const val PAUSED_POLL_MS = 1_000L
        const val SETTLE_MS = 300L
        const val CLIP_WAIT_MS = 40L
        const val WRITE_BLOCK_BYTES = 8_192
        const val TAIL_PADDING_MS = 800L
        const val STALL_LIMIT_MS = 5_000L
        const val UINT32_MASK = 0xFFFFFFFFL
    }
}

private const val TRACK_BUFFER_MS = 500L

/** Silence that lasts [realMs] once the track has played it at [speed]. */
private fun silenceBytes(
    realMs: Long,
    speed: Float,
    rate: Int,
): ByteArray = ByteArray(TtsPcmPlanning.msToFrames((realMs * speed).toLong(), rate).toInt() * BYTES_PER_FRAME)

private fun createSpeechTrack(rate: Int): AudioTrack {
    val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val wanted = TtsPcmPlanning.msToFrames(TRACK_BUFFER_MS, rate).toInt() * BYTES_PER_FRAME
    return AudioTrack
        .Builder()
        .setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        ).setAudioFormat(
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        ).setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(minimum, wanted))
        .build()
}
