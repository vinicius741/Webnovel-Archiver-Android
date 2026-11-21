package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate

/** How the page brings the spoken sentence into view. [code] is the page script's scroll argument. */
internal enum class ReadAlongScroll(
    val code: Int,
) {
    NONE(0),
    SMOOTH(1),
    INSTANT(2),

    /** Scroll only when the sentence is off screen: the page's own once-a-second check. */
    IF_OFF_SCREEN(3),
}

/**
 * What one reader page shows of read-aloud playback. [snapshot] is this chapter's playback (null
 * when the chapter is not being read aloud); [following] is whether the page keeps the spoken
 * sentence in view. Dragging the page turns following off; the pill, a double-tap on a sentence,
 * or a session that starts afresh in this chapter turn it back on.
 */
internal data class ReadAlongState(
    val snapshot: TtsPlaybackSnapshot? = null,
    val following: Boolean = true,
) {
    /** No highlight while the audio's sentences are not the page's ([TtsPlaybackSnapshot.olderText]). */
    val sentence: Int get() = snapshot?.takeUnless { it.olderText }?.chunkIndex ?: NO_SENTENCE

    /** The docked player replaces chapter navigation while this chapter is read aloud. */
    val showsPlayer: Boolean get() = snapshot != null

    /** "Back to current sentence": only while audio plays, a sentence is highlighted, and the page is not following it. */
    val showsPill: Boolean get() = !following && sentence != NO_SENTENCE && snapshot?.isPaused == false

    companion object {
        const val NO_SENTENCE = -1
    }
}

/** Pure read-along rules; [ReaderReadAlong] applies them to a page. */
internal object ReadAlongPlanning {
    fun afterPlayback(
        state: ReadAlongState,
        update: TtsPlaybackUpdate,
        storyId: String,
        chapterId: String,
    ): ReadAlongState {
        val snapshot = TtsPlaybackState.snapshotAfterUpdate(state.snapshot, update, storyId, chapterId)
        val startsHere = state.snapshot == null && snapshot != null
        return ReadAlongState(snapshot, following = state.following || startsHere)
    }

    /** With nothing highlighted there is nothing to stop following. */
    fun afterDrag(state: ReadAlongState): ReadAlongState =
        if (state.sentence == ReadAlongState.NO_SENTENCE) state else state.copy(following = false)

    fun afterFollow(state: ReadAlongState): ReadAlongState = state.copy(following = true)

    /** A new sentence, or following turned back on, brings the sentence into view. */
    fun scroll(
        before: ReadAlongState,
        after: ReadAlongState,
    ): ReadAlongScroll =
        when {
            after.sentence < 0 || !after.following -> ReadAlongScroll.NONE
            !before.following || before.sentence != after.sentence -> ReadAlongScroll.SMOOTH
            else -> ReadAlongScroll.NONE
        }

    /**
     * The chapter the reader should open when playback leaves this chapter for another chapter of
     * this story, or null. Only a page that is reading along ([ReadAlongState.showsPlayer]) follows
     * a chapter change; opening another chapter while a session sits elsewhere stays on that
     * chapter. [storyChapterIds] is the story's current chapter list, not the one the page opened
     * with: chapters synced since then must still be followed.
     */
    fun chapterToOpen(
        state: ReadAlongState,
        storyId: String,
        chapterId: String,
        snapshot: TtsPlaybackSnapshot?,
        storyChapterIds: Collection<String>,
    ): String? {
        if (!state.showsPlayer) return null
        return TtsPlaybackState.readerChapterTransition(storyId, chapterId, snapshot)?.takeIf { it in storyChapterIds }
    }

    /** The page script's view of [state]: "sentence following", e.g. "12 1"; -1 clears the highlight. */
    fun pageState(
        state: ReadAlongState,
        fingerDown: Boolean,
    ): String = "${state.sentence} ${if (state.following && !fingerDown) 1 else 0}"
}
