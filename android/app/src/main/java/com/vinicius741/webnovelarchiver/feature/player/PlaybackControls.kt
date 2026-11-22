package com.vinicius741.webnovelarchiver.feature.player

import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService

/**
 * The listener's playback commands, as the Listen screen, Now Playing and the mini player send them.
 * Screen models take this port so they can be tested without the playback service.
 */
interface PlaybackControls {
    fun playPause()

    fun stop()

    fun skipBack()

    fun skipForward()

    fun previousChapter()

    fun nextChapter()

    fun seekTo(positionMs: Long)

    fun setRate(rate: Float)

    /** Minutes until playback stops; 0 turns the timer off, -1 stops at the end of the chapter. */
    fun setSleepTimer(minutes: Int)

    fun jumpToHistory(entry: TtsHistoryEntry)

    /** Resumes the story's place in [chapterId], generating the chapter's audio first when it has none. */
    fun resume(
        storyId: String,
        chapterId: String,
    )
}

/** [PlaybackControls] through the playback service, which owns audio focus and the media session. */
internal class ServicePlaybackControls(
    private val host: ScreenHost,
) : PlaybackControls {
    private val context get() = host.app

    override fun playPause() = command(TtsForegroundService.ACTION_PLAY_PAUSE)

    override fun stop() = command(TtsForegroundService.ACTION_STOP)

    override fun skipBack() = command(TtsForegroundService.ACTION_SKIP_BACK)

    override fun skipForward() = command(TtsForegroundService.ACTION_SKIP_FORWARD)

    override fun previousChapter() = command(TtsForegroundService.ACTION_PREVIOUS_CHAPTER)

    override fun nextChapter() = command(TtsForegroundService.ACTION_NEXT_CHAPTER)

    override fun seekTo(positionMs: Long) = TtsForegroundService.seekTo(context, positionMs)

    override fun setRate(rate: Float) = TtsForegroundService.setRate(context, rate)

    override fun setSleepTimer(minutes: Int) = TtsForegroundService.setSleepTimer(context, minutes)

    override fun jumpToHistory(entry: TtsHistoryEntry) = TtsForegroundService.jumpToHistory(context, entry)

    override fun resume(
        storyId: String,
        chapterId: String,
    ) = host.playIfGenerated(storyId, chapterId) { TtsForegroundService.start(context, storyId, chapterId) }

    private fun command(action: String) = TtsForegroundService.command(context, action)
}
