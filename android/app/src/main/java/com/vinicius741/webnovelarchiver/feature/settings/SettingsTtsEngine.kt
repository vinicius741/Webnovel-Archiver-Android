package com.vinicius741.webnovelarchiver.feature.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroInstallPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

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

/** Picks which speech engine the app speaks with; voices differ per engine, so the voice resets. */
internal fun ScreenHost.showTtsEnginePicker(model: TtsSettingsScreenModel) {
    val state = model.state.value
    val selected = state.settings.enginePackage
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null

    fun option(
        packageName: String?,
        label: String,
    ) {
        val builtIn = KokoroVoices.model(packageName)
        val supporting = if (builtIn != null && !state.installed(builtIn)) "Not on this phone · generates on your computers" else null
        body.addView(
            makeSheetRow(app, label, supporting, R.drawable.wna_speaker, checked = packageName == selected) {
                sheet?.dismiss()
                if (packageName != selected) toast(model.chooseEngine(packageName))
            },
        )
    }
    option(null, TtsEnginePlanning.label(null, state.engines, state.phoneEngine))
    state.engines.forEach { option(it.packageName, it.label) }
    sheet = showBottomSheet(app, title = "Speech engine", subtitle = "Voices differ per engine", body = body)
}

/** Download progress and actions for one built-in model; [show] follows the page's state. */
internal class KokoroModelCard(
    private val host: ScreenHost,
    private val model: TtsSettingsScreenModel,
    private val builtIn: BuiltInModel,
) {
    private val context = host.app
    private val colors = ThemeManager.colors
    private val icon = ImageView(context)
    private val status = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, context.dp(2), 0, 0) }
    private val actions =
        WrapLayout(context).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
        }
    private var shownKind: String? = null
    private var shownState: KokoroModelState? = null

    val view: View =
        makeCard(context).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        icon,
                        LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply {
                            marginEnd =
                                context.dp(Spacing.MD + 2)
                        },
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                makeText(
                                    context,
                                    if (builtIn.retired) "Kokoro v0.19 voices · no longer used" else "${builtIn.family} voices",
                                    Type.TITLE_SMALL,
                                    colors.onSurface,
                                ).apply {
                                    textSize =
                                        TITLE_SP
                                },
                            )
                            addView(status)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
            )
            addView(
                actions,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        context.dp(Spacing.MD)
                },
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun show(page: TtsSettingsUiState) {
        val state = page.modelStates[builtIn] ?: return
        if (state == shownState) return
        shownState = state
        val runsHere = KokoroModelFiles.isDeviceSupported(builtIn)
        status.text =
            if (!runsHere && state is KokoroModelState.NotInstalled) {
                "This phone can't run ${builtIn.family} itself. Chapters generate on your computers."
            } else {
                KokoroInstallPlanning.describe(state, builtIn.modelPackage)
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
            is KokoroModelState.NotInstalled -> if (runsHere) host.addNotInstalledActions(actions, model, builtIn, state)
            is KokoroModelState.Failed ->
                actions.addView(
                    makeButton(context, "Try again", Btn.FILLED, R.drawable.wna_download) { model.installModel(builtIn) },
                )
            is KokoroModelState.Installed -> {
                host.addSpeedTestActions(actions, model, builtIn, page)
                actions.addView(
                    makeButton(
                        context,
                        "Remove",
                        Btn.TEXT,
                    ) { host.confirmKokoroRemoval(model, builtIn) }.apply { setTextColor(colors.error) },
                )
            }
            else -> actions.addView(makeButton(context, "Cancel", Btn.TEXT, R.drawable.wna_stop) { model.cancelInstall(builtIn) })
        }
    }
}

/** The speed test is a developer tool: its buttons show in debug builds only. */
private fun ScreenHost.addSpeedTestActions(
    actions: ViewGroup,
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
    page: TtsSettingsUiState,
) {
    if (!BuildConfig.DEBUG) return
    actions.addView(makeButton(app, "Test speed", Btn.SURFACE, R.drawable.wna_speed) { confirmKokoroSpeedTest(model, builtIn) })
    if (builtIn in page.speedTests) {
        actions.addView(makeButton(app, "Last results", Btn.SURFACE) { showLastKokoroSpeedTest(model, builtIn) })
    }
}

/** Lists the voices of the engine the page shows (a built-in model's even before it is downloaded) and saves the pick. */
internal fun ScreenHost.showTtsVoicePicker(model: TtsSettingsScreenModel) {
    val current = model.state.value.settings
    val engine = model.voiceEngine()
    if (!KokoroVoices.isEngine(engine)) toast("Loading voices")
    scope.launch {
        val voices = model.voices(engine)
        when {
            voices == null -> toast("The speech engine did not start")
            voices.isEmpty() -> toast("This speech engine has no offline voices")
            else -> showTtsVoiceSheet(voices, current.voiceIdentifier) { voice -> model.chooseVoice(voice) }
        }
    }
}

private const val ICON_DP = 24
private const val TITLE_SP = 15f
