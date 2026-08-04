package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsDescriptionPlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Description narration for the Details screen. Speaks the story's description through the same
 * engine/settings/foreground-service pipeline as chapter TTS (sessions keyed by
 * [TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID]) and owns the two on-screen affordances:
 *
 *  - the "Listen" button beside the description (engages, then doubles as pause/resume), and
 *  - the docked transport bar (prev sentence / play-pause / next sentence / stop) that stays
 *    visible no matter where the info panel has scrolled.
 *
 * System media controls + the TTS notification also pause/stop playback, as with chapter TTS.
 */

/** The single details collector is replaced whenever the screen is rebuilt. */
internal var activeDetailsTtsStateJob: Job? = null

/** Cancels the active details collector before its views are torn down or replaced. */
internal fun ScreenHost.detachDetailsTtsListener() {
    activeDetailsTtsStateJob?.cancel()
    activeDetailsTtsStateJob = null
}

/**
 * Seeds the initial Details transport/button state. Prefers the live engine snapshot; before the
 * engine publishes an authoritative state (cold start) it falls back to the persisted session so a
 * paused description narration still shows "Resume" when the screen reopens.
 */
internal fun detailsDescriptionSnapshotSeed(
    persisted: TtsSession?,
    update: TtsPlaybackUpdate,
    storyId: String,
): TtsPlaybackSnapshot? {
    val live = update.snapshot?.takeIf { it.storyId == storyId && TtsDescriptionPlanning.isDescriptionSession(it.chapterId) }
    if (live != null || update.isAuthoritative) return live
    val session =
        persisted?.takeIf { it.storyId == storyId && TtsDescriptionPlanning.isDescriptionSession(it.chapterId) }
            ?: return null
    // The Details transport never prints chunk counts, so the unknown total stays 0.
    return TtsPlaybackState.snapshotForSession(session, totalChunks = 0, isPlaying = !session.isPaused)
}

/** Engages description TTS, or pauses/resumes it when this story's description session is active. */
internal fun ScreenHost.toggleDescriptionTts(story: Story) {
    val snapshot = ttsEngine.playbackState.value.snapshot
    val isThisDescription =
        snapshot != null &&
            snapshot.storyId == story.id &&
            TtsDescriptionPlanning.isDescriptionSession(snapshot.chapterId)
    when {
        !isThisDescription -> TtsForegroundService.start(app, story.id, TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID)
        snapshot.isPaused -> TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
        else -> TtsForegroundService.command(app, TtsForegroundService.ACTION_PAUSE)
    }
}

/** Reflects the current description-playback state on the Listen button. */
internal fun renderDescriptionTtsButton(
    button: Button,
    storyId: String,
    snapshot: TtsPlaybackSnapshot?,
) {
    val active =
        snapshot != null &&
            snapshot.storyId == storyId &&
            TtsDescriptionPlanning.isDescriptionSession(snapshot.chapterId)
    val label =
        when {
            !active -> "Listen"
            snapshot.isPaused -> "Resume"
            else -> "Pause"
        }
    val icon =
        when {
            !active -> R.drawable.wna_speaker
            snapshot.isPaused -> R.drawable.wna_play
            else -> R.drawable.wna_pause
        }
    button.text = label
    button.contentDescription = label
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
        button.context.tintedIcon(icon, ThemeManager.colors.primary),
        null,
        null,
        null,
    )
}

/**
 * Collects the replaying playback state while this Details screen is alive to reveal/hide the
 * docked transport and keep the Listen button in sync. Only snapshots for THIS story's description
 * session are acted on, so chapter playback (owned by the reader transport + media notification)
 * never cross-talks.
 */
internal fun ScreenHost.observeDetailsDescriptionTts(
    story: Story,
    listenButton: Button?,
    transportBar: LinearLayout,
    transportPlayPause: ImageView?,
    initialSnapshot: TtsPlaybackSnapshot?,
    onSnapshot: (TtsPlaybackSnapshot?) -> Unit,
) {
    var snapshot = initialSnapshot
    renderDescriptionTtsState(listenButton, transportBar, transportPlayPause, story.id, snapshot)
    activeDetailsTtsStateJob?.cancel()
    activeDetailsTtsStateJob =
        scope.launch {
            ttsEngine.playbackState.collect { update ->
                val relevant =
                    TtsPlaybackState.snapshotAfterUpdate(
                        current = snapshot,
                        update = update,
                        storyId = story.id,
                        chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID,
                    )
                snapshot = relevant
                onSnapshot(relevant)
                renderDescriptionTtsState(listenButton, transportBar, transportPlayPause, story.id, relevant)
            }
        }
}

private fun renderDescriptionTtsState(
    listenButton: Button?,
    transportBar: LinearLayout,
    transportPlayPause: ImageView?,
    storyId: String,
    snapshot: TtsPlaybackSnapshot?,
) {
    // The bar is always present in the tree; visibility toggles so a session that starts after the
    // screen was built reveals it live (same pattern as the reader transport).
    transportBar.visibility = if (snapshot != null) View.VISIBLE else View.GONE
    transportPlayPause?.let { button ->
        val isPaused = snapshot?.isPaused != false
        button.setImageDrawable(
            button.context.tintedIcon(
                if (isPaused) R.drawable.wna_play else R.drawable.wna_pause,
                ThemeManager.colors.primary,
            ),
        )
        button.contentDescription = if (isPaused) "Play TTS" else "Pause TTS"
    }
    listenButton?.let { renderDescriptionTtsButton(it, storyId, snapshot) }
}
