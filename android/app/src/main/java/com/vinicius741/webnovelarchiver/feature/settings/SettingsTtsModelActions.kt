package com.vinicius741.webnovelarchiver.feature.settings

// The built-in model card's download and removal actions, beside the card in SettingsTtsEngine.kt.

import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroInstallPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.toast

internal fun ScreenHost.confirmKokoroDownload(
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
) {
    val pkg = builtIn.modelPackage
    confirm(
        "${builtIn.family} on this phone is a one-time ${KokoroInstallPlanning.megabytes(pkg.downloadBytes)} download " +
            "and uses about ${KokoroInstallPlanning.megabytes(pkg.unpackedBytes)}. Chapters can generate on your computers " +
            "without it; it lets this phone generate too and speak descriptions in this voice. " +
            "Keep the app open until it finishes; Wi-Fi is best.",
        "Download",
    ) {
        model.installModel(builtIn)
    }
}

/** A model card's buttons while [builtIn] is not installed: download, continue, or fetch new voices. */
internal fun ScreenHost.addNotInstalledActions(
    actions: ViewGroup,
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
    state: KokoroModelState.NotInstalled,
) {
    val context = actions.context
    when {
        state.updateBytes > 0L -> {
            actions.addView(makeButton(context, "Download new voices", Btn.FILLED, R.drawable.wna_download) { model.installModel(builtIn) })
            actions.addView(
                makeButton(context, "Remove", Btn.TEXT) { confirmKokoroRemoval(model, builtIn) }.apply {
                    setTextColor(ThemeManager.colors.error)
                },
            )
        }
        state.downloadedBytes > 0L ->
            actions.addView(makeButton(context, "Continue download", Btn.FILLED, R.drawable.wna_download) { model.installModel(builtIn) })
        else ->
            actions.addView(
                makeButton(
                    context,
                    "Download to this phone",
                    Btn.SURFACE,
                    R.drawable.wna_download,
                ) { confirmKokoroDownload(model, builtIn) },
            )
    }
}

internal fun ScreenHost.confirmKokoroRemoval(
    model: TtsSettingsScreenModel,
    builtIn: BuiltInModel,
) {
    confirm("Remove ${builtIn.family}'s voices from this phone? You can download them again later.", "Remove") {
        model.removeModel(builtIn)?.let(::toast)
    }
}
