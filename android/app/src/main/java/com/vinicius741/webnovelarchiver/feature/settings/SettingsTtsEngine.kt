package com.vinicius741.webnovelarchiver.feature.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroInstallPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
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

/**
 * How the Voice setting shows [identifier]: a built-in voice by name, an Android voice by its id. With
 * none chosen, a built-in engine ([enginePackage]) names the voice it uses by default.
 */
internal fun ttsVoiceLabel(
    identifier: String?,
    enginePackage: String? = null,
): String =
    KokoroVoices.forIdentifier(identifier)?.name
        ?: identifier
        ?: KokoroVoices.model(enginePackage)?.defaultVoice?.name
        ?: "Engine default"

internal fun ScreenHost.modelInstaller(model: BuiltInModel): KokoroModelInstaller = app.appContainer.modelInstallers.getValue(model)

/**
 * The built-in models whose card belongs on the screen: the chosen one, and any installed or
 * installing (so it can be removed). The chosen one comes first.
 */
internal fun ScreenHost.modelCards(settings: TtsSettings): List<BuiltInModel> {
    val chosen = KokoroVoices.model(settings.enginePackage)
    val others = BuiltInModel.entries.filter { it != chosen && modelInstaller(it).state.value != KokoroModelState.NotInstalled() }
    return listOfNotNull(chosen) + others
}

/** Picks which speech engine the app speaks with; voices differ per engine, so the voice resets. */
internal fun ScreenHost.showTtsEnginePicker(
    engines: List<TtsEngineInfo>,
    phoneEngine: String?,
    draft: () -> TtsSettings,
) {
    val selected = repository.ttsSettings.get().enginePackage

    fun choose(packageName: String?) {
        scope.launch {
            persistTtsSettings(draft().copy(enginePackage = packageName, voiceIdentifier = null))
            val model = KokoroVoices.model(packageName)
            // A built-in model needs no download: chapters generate on the computers.
            toast(
                if (model != null && !modelInstaller(model).isInstalled) {
                    "Engine changed. Chapters generate on your computers until you download it here."
                } else {
                    "Engine changed. Pick a voice from it, or keep its default."
                },
            )
            showTtsSettings()
        }
    }

    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null

    fun option(
        packageName: String?,
        label: String,
    ) {
        val model = KokoroVoices.model(packageName)
        val supporting =
            if (model != null &&
                !modelInstaller(model).isInstalled
            ) {
                "Not on this phone · generates on your computers"
            } else {
                null
            }
        body.addView(
            makeSheetRow(app, label, supporting, R.drawable.wna_speaker, checked = packageName == selected) {
                sheet?.dismiss()
                if (packageName != selected) choose(packageName)
            },
        )
    }
    option(null, TtsEnginePlanning.label(null, engines, phoneEngine))
    engines.forEach { option(it.packageName, it.label) }
    sheet = showBottomSheet(app, title = "Speech engine", subtitle = "Voices differ per engine", body = body)
}

/** Download progress and actions for one built-in model, updated live while the screen is open. */
internal fun ScreenHost.kokoroModelCard(model: BuiltInModel): View {
    val installer = modelInstaller(model)
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
                        addView(
                            makeText(context, "${model.family} voices", Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP },
                        )
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
            val runsHere = KokoroModelFiles.isDeviceSupported(model)
            status.text =
                if (!runsHere && state is KokoroModelState.NotInstalled) {
                    "This phone can't run ${model.family} itself. Chapters generate on your computers."
                } else {
                    KokoroInstallPlanning.describe(state, model.modelPackage)
                }
            val installed = state is KokoroModelState.Installed
            icon.setImageDrawable(
                context.tintedIcon(if (installed) R.drawable.wna_download_done else R.drawable.wna_download, colors.primary),
            )
            // Progress only changes the text; rebuild the buttons when the stage changes.
            val kind =
                if (state is KokoroModelState.NotInstalled) {
                    "paused=${state.downloadedBytes > 0L} update=${state.updateBytes > 0L}"
                } else {
                    state.javaClass.name
                }
            if (kind == shownKind) return
            shownKind = kind
            actions.removeAllViews()
            when (state) {
                is KokoroModelState.NotInstalled -> if (runsHere) addNotInstalledActions(actions, model, state)
                is KokoroModelState.Failed ->
                    actions.addView(
                        makeButton(context, "Try again", Btn.FILLED, R.drawable.wna_download) { installer.install() },
                    )
                is KokoroModelState.Installed -> {
                    // The speed test is a developer tool: debug builds only.
                    if (BuildConfig.DEBUG) {
                        actions.addView(
                            makeButton(context, "Test speed", Btn.SURFACE, R.drawable.wna_speed) { confirmKokoroSpeedTest(model) },
                        )
                        if (hasKokoroSpeedTest(model)) {
                            actions.addView(makeButton(context, "Last results", Btn.SURFACE) { showLastKokoroSpeedTest(model) })
                        }
                    }
                    actions.addView(
                        makeButton(context, "Remove", Btn.TEXT) { confirmKokoroRemoval(model) }.apply { setTextColor(colors.error) },
                    )
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

/** [draft] is what the settings screen currently shows, kept when picking a voice rebuilds it. */
internal fun ScreenHost.showTtsVoicePicker(draft: () -> TtsSettings = { repository.ttsSettings.get() }) {
    val current = draft()
    // A built-in model lists its voices even before it is downloaded, so a voice can be chosen early.
    val engine =
        current.enginePackage?.takeIf(KokoroVoices::isEngine)
            ?: TtsEnginePlanning.resolve(
                current.enginePackage,
                TtsEngineCatalog.usablePackages(app, app.appContainer.builtIns.installedEngines),
            )
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
