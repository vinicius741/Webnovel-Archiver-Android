package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.content.Intent
import android.view.WindowManager
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmark
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmarkReport
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
import java.util.concurrent.atomic.AtomicBoolean

private const val REPORT_FILE = "kokoro_speed_test.txt"

private val ScreenHost.speedTestFile: File get() = File(app.filesDir, REPORT_FILE)

/** Whether a finished speed test can be opened again from the Kokoro card. */
internal val ScreenHost.hasKokoroSpeedTest: Boolean get() = speedTestFile.isFile

internal fun ScreenHost.confirmKokoroSpeedTest() {
    confirm(
        "Times how fast Kokoro makes speech on this phone at several settings and how much of the " +
            "phone it uses. It takes about four minutes. Narration stops while it runs; your place is " +
            "saved. Keep this screen open.",
        "Start",
    ) { runKokoroSpeedTest() }
}

private fun ScreenHost.runKokoroSpeedTest() {
    // Stopping, not pausing: a paused chapter keeps rendering ahead, which would share the CPU.
    ttsEngine.stop()
    val settings = repository.ttsSettings.get()
    val voice = KokoroVoices.forIdentifier(settings.voiceIdentifier) ?: KokoroVoices.default
    val stopRequested = AtomicBoolean(false)
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Kokoro speed test")
            .setMessage("Starting")
            .setCancelable(false)
            .setNegativeButton("Stop", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
        stopRequested.set(true)
        button.isEnabled = false
        dialog.setMessage("Stopping after the current sentence")
    }
    scope.launch {
        // Generation shares the model and the CPU: stop it so the test measures Kokoro alone.
        val resumeNarration = app.appContainer.narration.pauseForSpeedTest()
        val result =
            KokoroBenchmark(app).run(
                speakerId = voice.speakerId,
                voiceName = voice.name,
                listeningRate = settings.rate,
                narrationModelLoaded = app.appContainer.kokoroRuntime.isLoaded,
                shouldStop = stopRequested::get,
            ) { progress -> app.runOnUiThread { if (dialog.isShowing && !stopRequested.get()) dialog.setMessage(progress) } }
        if (resumeNarration) withContext(Dispatchers.IO) { runCatching { app.appContainer.narration.resume() } }
        val report = KokoroBenchmarkReport.report(result)
        Timber.i("Kokoro speed test\n%s", report)
        withContext(Dispatchers.IO) { runCatching { speedTestFile.writeText(report) } }
        if (dialog.isShowing) dialog.dismiss()
        showKokoroSpeedTestReport(report)
        showTtsSettings(keepDraft = true)
    }
}

/** The last speed test's results, if one has finished. */
internal fun ScreenHost.showLastKokoroSpeedTest() {
    scope.launch {
        val report = withContext(Dispatchers.IO) { runCatching { speedTestFile.readText() }.getOrNull() }
        if (report == null) toast("No speed test results yet") else showKokoroSpeedTestReport(report)
    }
}

/** Shows the readable part; Share and Copy send everything, with the data for later analysis. */
private fun ScreenHost.showKokoroSpeedTestReport(report: String) {
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Kokoro speed test")
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
                .putExtra(Intent.EXTRA_SUBJECT, "Kokoro speed test")
                .putExtra(Intent.EXTRA_TEXT, report)
        app.startActivity(Intent.createChooser(intent, "Share speed test results"))
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        copyToClipboard("Kokoro speed test", report)
        toast("Speed test results copied")
    }
}
