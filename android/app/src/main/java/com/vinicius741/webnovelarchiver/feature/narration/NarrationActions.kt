package com.vinicius741.webnovelarchiver.feature.narration

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.withStarted
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationVoice
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.operations.ListenStartUi
import com.vinicius741.webnovelarchiver.operations.ListenStarts
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal val ScreenHost.narration: NarrationCoordinator get() = container.narration

private val ScreenHost.container get() = app.appContainer

/**
 * Plays a chapter's generated audio, or, when it has none yet, generates it first (at the front of
 * the queue) and starts playing once it is ready. Every "listen" entry point goes through this
 * ([ListenStarts.playIfGenerated]).
 */
internal fun ScreenHost.playIfGenerated(
    storyId: String,
    chapterId: String,
    start: () -> Unit,
) = container.listenStarts.playIfGenerated(ActivityListenStartUi(this), storyId, chapterId, start)

/** Plays [chapterId] itself ([ListenStarts.playChapter]). */
internal fun ScreenHost.playChapter(
    storyId: String,
    chapterId: String,
) = container.listenStarts.playChapter(ActivityListenStartUi(this), storyId, chapterId)

/** Continues the story's audio from its own listening place ([ListenStarts.continueListening]). */
internal fun ScreenHost.continueListening(
    storyId: String,
    fallbackChapterId: String,
) = container.listenStarts.continueListening(ActivityListenStartUi(this), storyId, fallbackChapterId)

/** A listen request's screen side: toasts, the permission prompt and the playback service, on this activity. */
private class ActivityListenStartUi(
    private val host: ScreenHost,
) : ListenStartUi {
    override val scope get() = host.scope

    override fun toast(text: String) = host.toast(text)

    override fun requestNotificationPermission() = host.requestNotificationPermissionForDownload()

    override suspend fun awaitForeground() = host.app.lifecycle.withStarted {}

    override fun start(
        storyId: String,
        chapterId: String,
    ) = TtsForegroundService.start(host.app, storyId, chapterId)

    override fun startFromBeginning(
        storyId: String,
        chapterId: String,
    ) = TtsForegroundService.startFromChunk(host.app, storyId, chapterId, 0)

    override fun resumeSession() = TtsForegroundService.command(host.app, TtsForegroundService.ACTION_RESUME_SESSION)
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
