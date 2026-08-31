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
 * Description narration for the Details screen: same engine/settings/foreground-service pipeline
 * as chapter TTS (sessions keyed by [TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID]). Owns the
 * "Listen" button and the docked transport bar; media controls and the TTS notification also
 * pause/stop playback.
 */

/** Replaced whenever the Details screen is rebuilt. */
internal var activeDetailsTtsStateJob: Job? = null

internal fun ScreenHost.detachDetailsTtsListener() {
    activeDetailsTtsStateJob?.cancel()
    activeDetailsTtsStateJob = null
}

/** Seeds initial state: prefers the live snapshot; before the engine is authoritative (cold
 *  start) falls back to the persisted session so paused narration still shows "Resume". */
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

/** Collects the replaying playback state while this Details screen is alive to drive the docked
 *  transport and Listen button; ignores other stories/sessions so chapter playback never
 *  cross-talks. */
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
    // The bar stays in the tree; visibility toggles so a session starting later reveals it live.
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
