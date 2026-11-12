package com.vinicius741.webnovelarchiver.feature.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon
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

    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null

    fun option(
        packageName: String?,
        label: String,
    ) {
        val needsDownload = KokoroVoices.isEngine(packageName) && !installer.isInstalled
        val supporting = if (needsDownload) "${KokoroInstallPlanning.megabytes(KokoroModel.ARCHIVE_BYTES)} download" else null
        body.addView(
            makeSheetRow(app, label, supporting, R.drawable.wna_speaker, checked = packageName == selected) {
                sheet?.dismiss()
                when {
                    packageName == selected -> Unit
                    needsDownload -> confirmKokoroDownload { choose(packageName) }
                    else -> choose(packageName)
                }
            },
        )
    }
    option(null, TtsEnginePlanning.label(null, engines, phoneEngine))
    engines.forEach { option(it.packageName, it.label) }
    sheet = showBottomSheet(app, title = "Speech engine", subtitle = "Voices differ per engine", body = body)
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
        val icon = ImageView(context)
        val status = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(Spacing.MD + 2) })
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeText(context, "Kokoro voices", Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP })
                        addView(status)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )
        val actions =
            WrapLayout(context).apply {
                horizontalSpacingDp = Spacing.SM
                verticalSpacingDp = Spacing.SM
            }
        addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.MD)
            },
        )
        var shownKind: String? = null

        fun render(state: KokoroModelState) {
            status.text = KokoroInstallPlanning.describe(state)
            val installed = state is KokoroModelState.Installed
            icon.setImageDrawable(
                context.tintedIcon(if (installed) R.drawable.wna_download_done else R.drawable.wna_download, colors.primary),
            )
            // Progress only changes the text; rebuild the buttons when the stage changes.
            val kind = if (state is KokoroModelState.NotInstalled) "paused=${state.downloadedBytes > 0L}" else state.javaClass.name
            if (kind == shownKind) return
            shownKind = kind
            actions.removeAllViews()
            when (state) {
                is KokoroModelState.NotInstalled ->
                    if (state.downloadedBytes > 0L) {
                        actions.addView(
                            makeButton(context, "Continue download", Btn.FILLED, R.drawable.wna_download) { installer.install() },
                        )
                    } else {
                        actions.addView(
                            makeButton(context, "Install voices", Btn.FILLED, R.drawable.wna_download) { confirmKokoroDownload {} },
                        )
                    }
                is KokoroModelState.Failed ->
                    actions.addView(
                        makeButton(context, "Try again", Btn.FILLED, R.drawable.wna_download) { installer.install() },
                    )
                is KokoroModelState.Installed -> {
                    actions.addView(makeButton(context, "Test speed", Btn.SURFACE, R.drawable.wna_speed) { confirmKokoroSpeedTest() })
                    if (hasKokoroSpeedTest) actions.addView(makeButton(context, "Last results", Btn.SURFACE) { showLastKokoroSpeedTest() })
                    actions.addView(makeButton(context, "Remove", Btn.TEXT) { confirmKokoroRemoval() }.apply { setTextColor(colors.error) })
                }
                else -> actions.addView(makeButton(context, "Cancel", Btn.TEXT, R.drawable.wna_stop) { installer.cancel() })
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            showTtsSettings(keepDraft = true)
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
            else -> showTtsVoiceSheet(voices, current.voiceIdentifier) { voice -> saveTtsVoice(voice, draft()) }
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

private const val ICON_DP = 24
private const val TITLE_SP = 15f
