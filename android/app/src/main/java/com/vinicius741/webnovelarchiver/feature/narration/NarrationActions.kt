package com.vinicius741.webnovelarchiver.feature.narration

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationVoice
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal val ScreenHost.narration: NarrationCoordinator get() = app.appContainer.narration

/** The chapter waiting for its audio so it can start playing; a newer request replaces it. */
private var pendingPlay: Job? = null

/**
 * Plays a chapter's generated audio, or, when it has none yet, generates it first (at the front of
 * the queue) and starts playing once it is ready. Every "listen" entry point goes through this.
 */
internal fun ScreenHost.playIfGenerated(
    storyId: String,
    chapterId: String,
    start: () -> Unit,
) {
    pendingPlay?.cancel()
    pendingPlay = null
    scope.launch {
        val ready =
            runCatching { narration.isReady(storyId, chapterId) }
                .onFailure { Timber.w(it, "Narration status failed") }
                .getOrDefault(false)
        if (ready) {
            start()
            return@launch
        }
        val queued =
            withContext(Dispatchers.IO) {
                runCatching {
                    narration.enqueue(
                        storyId,
                        listOf(chapterId),
                        narration.currentVoice(),
                        NarrationOrigin.MANUAL,
                        front = true,
                    )
                }
            }
        if (queued.isFailure) {
            Timber.e(queued.exceptionOrNull(), "Narration enqueue failed")
            toast("Could not generate the audio")
            return@launch
        }
        toast("Generating \"${narration.chapterTitle(storyId, chapterId)}\". It plays when it's ready.")
        requestNotificationPermissionForDownload()
        pendingPlay = scope.launch { awaitAudioThenPlay(storyId, chapterId, start) }
    }
}

/** Starts [start] once the chapter's audio is published; gives up if its job goes away or fails, or other audio starts. */
private suspend fun ScreenHost.awaitAudioThenPlay(
    storyId: String,
    chapterId: String,
    start: () -> Unit,
) {
    val outcome =
        combine(narration.packsRevision, narration.queueState, ttsEngine.playbackState) { _, queue, playback ->
            val snapshot = playback.snapshot
            val job = queue.jobs.firstOrNull { it.storyId == storyId && it.chapterId == chapterId }
            when {
                snapshot != null && snapshot.isPlaying && snapshot.chapterId != chapterId -> PendingPlay.GIVE_UP
                runCatching { narration.isReady(storyId, chapterId) }.getOrDefault(false) -> PendingPlay.READY
                job == null || job.state == NarrationJobState.FAILED -> PendingPlay.GIVE_UP
                else -> PendingPlay.WAIT
            }
        }.first { it != PendingPlay.WAIT }
    if (outcome == PendingPlay.READY) start()
}

private enum class PendingPlay { WAIT, READY, GIVE_UP }

/**
 * Plays [chapterId] itself: from the story's saved listening place when that place is in this
 * chapter, else from its start. "Continue" entry points use [TtsForegroundService.start] instead.
 */
internal fun ScreenHost.playChapter(
    storyId: String,
    chapterId: String,
) {
    playIfGenerated(storyId, chapterId) {
        scope.launch {
            val position = repository.getTtsStoryPosition(storyId)
            if (position != null && !position.caughtUp && position.chapterId == chapterId) {
                TtsForegroundService.start(app, storyId, chapterId)
            } else {
                TtsForegroundService.startFromChunk(app, storyId, chapterId, 0)
            }
        }
    }
}

/** Queues chapters for generation, offering once to let generation run with the screen off. */
internal fun ScreenHost.queueNarration(
    storyId: String,
    chapterIds: List<String>,
    origin: NarrationOrigin = NarrationOrigin.MANUAL,
    front: Boolean = false,
    voice: NarrationVoice? = null,
) {
    if (chapterIds.isEmpty()) {
        toast("Nothing to generate")
        return
    }
    scope.launch {
        val result =
            withContext(Dispatchers.IO) {
                runCatching { narration.enqueue(storyId, chapterIds, voice ?: narration.currentVoice(), origin, front) }
            }
        if (result.isFailure) {
            Timber.e(result.exceptionOrNull(), "Narration enqueue failed")
            toast("Could not queue the audio")
            return@launch
        }
        toast(if (chapterIds.size == 1) "Chapter queued for audio" else "${chapterIds.size} chapters queued for audio")
        requestNotificationPermissionForDownload()
        maybeOfferScreenOffGeneration(chapterIds.size)
    }
}

private fun ScreenHost.maybeOfferScreenOffGeneration(chapters: Int) {
    if (chapters < 2 || app.isIgnoringBatteryOptimizations() || narration.queueState.value.screenOffPromptShown) return
    scope.launch(Dispatchers.IO) { runCatching { narration.markScreenOffPromptShown() } }
    confirm(
        "Android pauses background work while the phone sleeps. To keep generating audio with the screen off, " +
            "allow this app to run without battery restrictions.",
        confirmLabel = "Allow",
    ) { app.requestIgnoreBatteryOptimizations() }
}

internal fun Context.isIgnoringBatteryOptimizations(): Boolean =
    runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }
        .getOrDefault(true)

/** The app is sideloaded, so the Play policy behind this lint check does not apply. */
@SuppressLint("BatteryLife")
internal fun Context.requestIgnoreBatteryOptimizations() {
    runCatching {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** Runs a narration change off the main thread and reports a failure. */
internal fun ScreenHost.narrationAction(
    failure: String,
    block: suspend NarrationCoordinator.() -> Unit,
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { narration.block() } }
        result.exceptionOrNull()?.let {
            Timber.e(it, failure)
            toast(failure)
        }
    }
}

/** A card added to this layout ([card] only builds one). */
internal fun ViewGroup.addCard(block: LinearLayout.() -> Unit) {
    addView(card(block = block))
}
