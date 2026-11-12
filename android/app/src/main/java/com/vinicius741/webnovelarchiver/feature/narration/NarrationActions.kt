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
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationVoice
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal val ScreenHost.narration: NarrationCoordinator get() = app.appContainer.narration

/**
 * Starts a chapter only when its audio is generated; otherwise opens the story's narration screen
 * on that chapter, where it can be generated. Every "listen" entry point goes through this.
 */
internal fun ScreenHost.playIfGenerated(
    storyId: String,
    chapterId: String,
    start: () -> Unit,
) {
    scope.launch {
        val ready =
            runCatching {
                narration.isReady(
                    storyId,
                    chapterId,
                )
            }.onFailure { Timber.w(it, "Narration status failed") }.getOrDefault(false)
        if (ready) {
            start()
        } else {
            toast("Generate this chapter's audio first")
            showNarrationStory(storyId, chapterId)
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
