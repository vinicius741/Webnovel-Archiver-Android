package com.vinicius741.webnovelarchiver.tts

import android.os.SystemClock
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning

/** The session and chapter the engine has loaded, and everything derived from them. */
internal class TtsLoadedSession {
    var session: TtsSession? = null
    var chunks: List<String> = emptyList()
        private set
    var narration: List<TtsNarrationChunk> = emptyList()
        private set
    var timeline: TtsTimeline? = null
        private set
    var currentItem = 0
    var currentOffsetMs = 0L
    private var spokenTitle: String? = null

    /** Identifies the loaded text, to match it with the chapter's generated audio. */
    var fingerprint: String = ""
        private set

    /**
     * A story description speaks through the speech engine; a chapter plays only its generated audio.
     */
    val speaksLive: Boolean get() = session?.chapterId?.let(TtsDescriptionPlanning::isDescriptionSession) == true
    private var chapterIndex = -1
    private var chapterCount = 0

    val hasChapter: Boolean get() = session != null && chunks.isNotEmpty()

    val items: List<TtsTimelineItem> get() = timeline?.items.orEmpty()

    val currentPoint: TtsTimelinePoint get() = TtsTimelinePoint(currentItem, currentOffsetMs)

    fun load(prepared: PreparedTtsPlayback) {
        chunks = prepared.chunks
        narration = prepared.narration
        fingerprint = NarrationPlanning.fingerprint(prepared.chunks, TtsTextPreparation.CHUNKING_VERSION)
        spokenTitle = prepared.spokenTitle
        val loadedItems = TtsNarrationPlanning.items(prepared.narration, prepared.spokenTitle, prepared.settings)
        timeline = TtsTimeline(loadedItems, prepared.settings.phrasePauseMs)
        val startsAtTitle = prepared.spokenTitle != null && prepared.startIndex == 0 && prepared.startOffsetMs == 0L
        currentItem = TtsNarrationPlanning.itemIndexForChunk(loadedItems, prepared.startIndex, includeTitle = startsAtTitle)
        currentOffsetMs = prepared.startOffsetMs
        chapterIndex = prepared.story.chapters.indexOfFirst { it.id == prepared.chapter.id }
        chapterCount = prepared.story.chapters.size
        val history = session?.takeIf { it.storyId == prepared.story.id }?.history ?: prepared.history
        session =
            TtsSession(
                storyId = prepared.story.id,
                storyTitle = prepared.story.title,
                chapterId = prepared.chapter.id,
                chapterTitle = prepared.chapter.title,
                currentChunkIndex = prepared.startIndex,
                isPaused = false,
                wasPlaying = true,
                voiceIdentifier = prepared.settings.voiceIdentifier,
                rate = prepared.settings.rate,
                pitch = prepared.settings.pitch,
                chunkOffsetMs = prepared.startOffsetMs,
                anchorText = TtsPositionPlanning.anchorFor(prepared.chunks.getOrNull(prepared.startIndex)),
                chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                history = history,
            )
    }

    /** New pauses for the loaded chapter; item numbers stay the same, so the place is unaffected. */
    fun applyPacing(settings: TtsSettings) {
        timeline?.updatePacing(TtsNarrationPlanning.items(narration, spokenTitle, settings), settings.phrasePauseMs)
    }

    fun clearChapter() {
        fingerprint = ""
        chunks = emptyList()
        narration = emptyList()
        timeline = null
    }

    fun savePoint(point: TtsTimelinePoint): TtsChunkPoint = TtsNarrationPlanning.savePoint(items, point)

    /** Moves the saved place; [playing] decides whether the session reads as playing or paused. */
    fun writePosition(
        point: TtsChunkPoint,
        playing: Boolean,
    ): TtsSession? {
        session =
            located(point)?.copy(
                isPaused = !playing,
                wasPlaying = playing,
                updatedAt = System.currentTimeMillis(),
            )
        return session
    }

    /** The place to remember when playback ends, leaving the session's other state as it is. */
    fun writeFinalPosition(point: TtsTimelinePoint) {
        session = located(savePoint(point))?.copy(updatedAt = System.currentTimeMillis())
    }

    private fun located(point: TtsChunkPoint): TtsSession? =
        session?.copy(
            currentChunkIndex = point.chunkIndex,
            chunkOffsetMs = point.offsetMs,
            anchorText = TtsPositionPlanning.anchorFor(chunks.getOrNull(point.chunkIndex)),
            chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
        )

    /** Remembers where the listener is before a jump, so it can be undone. */
    fun recordJump(point: TtsTimelinePoint) {
        val current = session ?: return
        if (TtsDescriptionPlanning.isDescriptionSession(current.chapterId) || chunks.isEmpty()) return
        val place = savePoint(point)
        val entry =
            TtsHistoryEntry(
                chapterId = current.chapterId,
                chapterTitle = current.chapterTitle,
                chunkIndex = place.chunkIndex,
                chunkOffsetMs = place.offsetMs,
                anchorText = TtsPositionPlanning.anchorFor(chunks.getOrNull(place.chunkIndex)),
                chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                recordedAt = System.currentTimeMillis(),
            )
        session = current.copy(history = TtsPositionPlanning.withHistoryEntry(current.history, entry))
    }

    fun snapshot(
        isPlaying: Boolean,
        point: TtsTimelinePoint,
        isLiveOutput: Boolean,
        sleepTimer: TtsSleepTimerMode,
    ): TtsPlaybackSnapshot? {
        val base = TtsPlaybackState.snapshotForSession(session, chunks.size, isPlaying) ?: return null
        return base.copy(
            sleepTimerTargetEpochMs = (sleepTimer as? TtsSleepTimerMode.Duration)?.targetEpochMs,
            sleepTimerEndOfChapter = sleepTimer is TtsSleepTimerMode.EndOfChapter,
            positionMs = timeline?.positionMs(point.item, point.offsetMs) ?: 0L,
            durationMs = timeline?.totalMs() ?: 0L,
            timingExact = !isLiveOutput && timeline?.isExact == true,
            capturedAtElapsedMs = SystemClock.elapsedRealtime(),
            isLiveOutput = isLiveOutput,
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
        )
    }
}
