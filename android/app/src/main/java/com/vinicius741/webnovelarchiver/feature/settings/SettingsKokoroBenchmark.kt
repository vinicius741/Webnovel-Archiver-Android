package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmark
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmarkReport
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Each model keeps its last report; Kokoro v0.19's keeps the name it always had. */
internal fun speedTestFile(
    context: Context,
    model: BuiltInModel,
): File = File(context.filesDir, if (model == BuiltInModel.KOKORO_V019) "kokoro_speed_test.txt" else "${model.dirName}_speed_test.txt")

/**
 * Times [model] on this phone with narration stopped (so the test measures the model alone), saves
 * the report next to the app's files and returns it. Runs for [TtsSettingsScreenModel.startSpeedTest].
 */
internal suspend fun runKokoroSpeedTest(
    host: ScreenHost,
    model: BuiltInModel,
    settings: TtsSettings,
    shouldStop: () -> Boolean,
    onProgress: (String) -> Unit,
): String {
    val app = host.app
    val container = app.appContainer
    // Stopping, not pausing: a paused chapter keeps rendering ahead, which would share the CPU.
    host.ttsEngine.stop()
    val voice = KokoroVoices.voiceFor(model, settings.voiceIdentifier)
    // Generation shares the model and the CPU: stop it so the test measures Kokoro alone.
    val resumeNarration = container.narration.pauseForSpeedTest()
    val result =
        KokoroBenchmark(app, KokoroModelFiles(app, model)).run(
            voice = voice,
            listeningRate = settings.rate,
            narrationModelLoaded = container.builtIns.runtime(model).isLoaded,
            shouldStop = shouldStop,
            onProgress = onProgress,
        )
    if (resumeNarration) withContext(Dispatchers.IO) { runCatching { container.narration.resume() } }
    val report = KokoroBenchmarkReport.report(result)
    Timber.i("Kokoro speed test\n%s", report)
    withContext(Dispatchers.IO) { runCatching { speedTestFile(app, model).writeText(report) } }
    return report
}

internal fun ScreenHost.confirmKokoroSpeedTest(
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
) {
    confirm(
        "Times how fast ${builtIn.family} makes speech on this phone at several settings and how much of the " +
            "phone it uses. It takes a few minutes. Narration stops while it runs; your place is " +
            "saved. Keep this screen open.",
        "Start",
    ) {
        model.startSpeedTest(builtIn)
        showKokoroSpeedTestProgress(model, builtIn)
    }
}

/**
 * The progress dialog of the running speed test. It follows the model's [TtsSettingsScreenModel.speedTest]
 * on its own (it outlives a fold's rebuild of the page) and shows the report when the test ends.
 */
private fun ScreenHost.showKokoroSpeedTestProgress(
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
) {
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("${builtIn.family} speed test")
            .setMessage("Starting")
            .setCancelable(false)
            .setNegativeButton("Stop", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
        button.isEnabled = false
        model.stopSpeedTest()
    }
    val follow =
        scope.launch {
            model.speedTest.collect { run ->
                if (run == null) {
                    dialog.dismiss()
                    return@collect
                }
                val report = run.report
                if (report == null) {
                    dialog.setMessage(run.message)
                } else {
                    dialog.dismiss()
                    model.speedTestShown()
                    showKokoroSpeedTestReport(report)
                }
            }
        }
    dialog.setOnDismissListener { follow.cancel() }
}

/** The last speed test's results, if one has finished. */
internal fun ScreenHost.showLastKokoroSpeedTest(
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
) {
    scope.launch {
        val report = model.lastSpeedTest(builtIn)
        if (report == null) toast("No speed test results yet") else showKokoroSpeedTestReport(report)
    }
}

/** Shows the readable part; Share and Copy send everything, with the data for later analysis. */
private fun ScreenHost.showKokoroSpeedTestReport(report: String) {
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Speed test")
            // The dialog title already names the test, so its first line is left out.
            .setMessage(report.substringBefore(KokoroBenchmarkReport.DATA_MARKER).substringAfter('\n').trim())
            .setPositiveButton("Share", null)
            .setNeutralButton("Copy", null)
            .setNegativeButton("Close", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
    // Sharing and copying keep the results open, so both can be used.
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Voice speed test")
                .putExtra(Intent.EXTRA_TEXT, report)
        app.startActivity(Intent.createChooser(intent, "Share speed test results"))
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        copyToClipboard("Voice speed test", report)
        toast("Speed test results copied")
    }
}
