package com.vinicius741.webnovelarchiver.feature.settings

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroInstallPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelInstaller
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.showTtsVoiceDialog
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Saves [settings] and hands them to the loaded chapter, which picks up a new voice in place. */
internal suspend fun ScreenHost.persistTtsSettings(settings: TtsSettings) {
    repository.ttsSettings.save(settings)
    ttsEngine.applySettings(repository.ttsSettings.get())
}

/** How the Voice setting shows [identifier]: a Kokoro voice by name, an Android voice by its id. */
internal fun ttsVoiceLabel(identifier: String?): String = KokoroVoices.forIdentifier(identifier)?.name ?: identifier ?: "Engine default"

private val ScreenHost.kokoroInstaller: KokoroModelInstaller get() = app.appContainer.kokoroModel

/** The Kokoro card belongs on the screen while Kokoro is chosen, installed, or installing. */
internal fun ScreenHost.showsKokoroCard(settings: TtsSettings): Boolean =
    KokoroVoices.isEngine(settings.enginePackage) || kokoroInstaller.state.value != KokoroModelState.NotInstalled()

/** Picks which speech engine the app speaks with; voices differ per engine, so the voice resets. */
internal fun ScreenHost.showTtsEnginePicker(
    engines: List<TtsEngineInfo>,
    phoneEngine: String?,
    draft: () -> TtsSettings,
) {
    val selected = repository.ttsSettings.get().enginePackage
    val installer = kokoroInstaller

    fun choose(packageName: String?) {
        scope.launch {
            persistTtsSettings(draft().copy(enginePackage = packageName, voiceIdentifier = null))
            toast("Engine changed. Pick a voice from it, or keep its default.")
            showTtsSettings()
        }
    }

    fun option(
        packageName: String?,
        label: String,
    ): OptionsDialogItem.Option {
        val needsDownload = KokoroVoices.isEngine(packageName) && !installer.isInstalled
        val shown = if (needsDownload) "$label · ${KokoroInstallPlanning.megabytes(KokoroModel.ARCHIVE_BYTES)} download" else label
        return OptionsDialogItem.Option(if (packageName == selected) "✓  $shown" else shown) {
            when {
                packageName == selected -> Unit
                needsDownload -> confirmKokoroDownload { choose(packageName) }
                else -> choose(packageName)
            }
        }
    }
    showStyledOptionsDialog(
        "Speech engine",
        listOf(option(null, TtsEnginePlanning.label(null, engines, phoneEngine))) +
            engines.map { option(it.packageName, it.label) },
    )
}

private fun ScreenHost.confirmKokoroDownload(onStarted: () -> Unit) {
    confirm(
        "Kokoro's voices are a one-time ${KokoroInstallPlanning.megabytes(KokoroModel.ARCHIVE_BYTES)} download " +
            "and use about ${KokoroInstallPlanning.megabytes(KokoroModel.UNPACKED_BYTES)} on this phone. " +
            "Keep the app open until it finishes; Wi-Fi is best.",
        "Download",
    ) {
        kokoroInstaller.install()
        onStarted()
    }
}

/** Download progress and actions for the Kokoro model, updated live while the screen is open. */
internal fun ScreenHost.kokoroModelCard(): View {
    val installer = kokoroInstaller
    val colors = ThemeManager.colors
    return makeCard(app).apply {
        text("Kokoro voices", Type.TITLE_SMALL)
        val status =
            text("", Type.BODY_SMALL, colors.onSurfaceVariant)
                .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(Space.XS) }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(actions)
        var shownKind: String? = null

        fun render(state: KokoroModelState) {
            status.text = KokoroInstallPlanning.describe(state)
            // Progress only changes the text; rebuild the buttons when the stage changes.
            val kind = if (state is KokoroModelState.NotInstalled) "paused=${state.downloadedBytes > 0L}" else state.javaClass.name
            if (kind == shownKind) return
            shownKind = kind
            actions.removeAllViews()
            actions.flow {
                when (state) {
                    is KokoroModelState.NotInstalled ->
                        if (state.downloadedBytes > 0L) {
                            button("Continue download", Btn.TONAL, R.drawable.wna_download) { installer.install() }
                        } else {
                            button("Download", Btn.TONAL, R.drawable.wna_download) { confirmKokoroDownload {} }
                        }
                    is KokoroModelState.Failed -> button("Try again", Btn.TONAL, R.drawable.wna_download) { installer.install() }
                    is KokoroModelState.Installed -> button("Remove", Btn.TEXT, R.drawable.wna_delete) { confirmKokoroRemoval() }
                    else -> button("Cancel", Btn.TEXT, R.drawable.wna_stop) { installer.cancel() }
                }
            }
        }
        render(installer.state.value)
        var watcher: Job? = null
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    watcher = scope.launch { installer.state.collect(::render) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    watcher?.cancel()
                    watcher = null
                }
            },
        )
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.SM)
                bottomMargin = dp(Space.MD)
            }
    }
}

private fun ScreenHost.confirmKokoroRemoval() {
    confirm("Remove Kokoro's voices from this phone? You can download them again later.", "Remove") {
        kokoroInstaller.remove()
        scope.launch {
            val settings = repository.ttsSettings.get()
            if (KokoroVoices.isEngine(settings.enginePackage)) {
                persistTtsSettings(settings.copy(enginePackage = null, voiceIdentifier = null))
                toast("Kokoro removed. Speaking with the phone's engine again.")
            }
            showTtsSettings()
        }
    }
}

/** [draft] is what the settings screen currently shows, kept when picking a voice rebuilds it. */
internal fun ScreenHost.showTtsVoicePicker(draft: () -> TtsSettings = { repository.ttsSettings.get() }) {
    val current = draft()
    // Kokoro lists its voices even before its model is downloaded, so a voice can be chosen early.
    val engine =
        current.enginePackage?.takeIf(KokoroVoices::isEngine)
            ?: TtsEnginePlanning.resolve(current.enginePackage, TtsEngineCatalog.usablePackages(app, kokoroInstaller.isInstalled))
    if (!KokoroVoices.isEngine(engine)) toast("Loading voices")
    scope.launch {
        val voices = TtsEngineCatalog.voices(app, engine)
        when {
            voices == null -> toast("The speech engine did not start")
            voices.isEmpty() -> toast("This speech engine has no offline voices")
            else -> showTtsVoiceDialog(voices, current.voiceIdentifier) { voice -> saveTtsVoice(voice, draft()) }
        }
    }
}

private fun ScreenHost.saveTtsVoice(
    voice: VoiceInfo?,
    current: TtsSettings,
) {
    scope.launch {
        persistTtsSettings(current.copy(voiceIdentifier = voice?.identifier))
        showTtsSettings()
    }
}
