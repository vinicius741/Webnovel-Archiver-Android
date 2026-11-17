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
import kotlinx.coroutines.launch

internal fun ScreenHost.confirmKokoroDownload(
    model: BuiltInModel,
    onStarted: () -> Unit,
) {
    val pkg = model.modelPackage
    confirm(
        "${model.family} on this phone is a one-time ${KokoroInstallPlanning.megabytes(pkg.downloadBytes)} download " +
            "and uses about ${KokoroInstallPlanning.megabytes(pkg.unpackedBytes)}. Chapters can generate on your computers " +
            "without it; it lets this phone generate too and speak descriptions in this voice. " +
            "Keep the app open until it finishes; Wi-Fi is best.",
        "Download",
    ) {
        modelInstaller(model).install()
        onStarted()
    }
}

/** A model card's buttons while [model] is not installed: download, continue, or fetch new voices. */
internal fun ScreenHost.addNotInstalledActions(
    actions: ViewGroup,
    model: BuiltInModel,
    state: KokoroModelState.NotInstalled,
) {
    val context = actions.context
    val installer = modelInstaller(model)
    when {
        state.updateBytes > 0L -> {
            actions.addView(makeButton(context, "Download new voices", Btn.FILLED, R.drawable.wna_download) { installer.install() })
            actions.addView(
                makeButton(context, "Remove", Btn.TEXT) { confirmKokoroRemoval(model) }.apply { setTextColor(ThemeManager.colors.error) },
            )
        }
        state.downloadedBytes > 0L ->
            actions.addView(makeButton(context, "Continue download", Btn.FILLED, R.drawable.wna_download) { installer.install() })
        else ->
            actions.addView(
                makeButton(context, "Download to this phone", Btn.SURFACE, R.drawable.wna_download) { confirmKokoroDownload(model) {} },
            )
    }
}

internal fun ScreenHost.confirmKokoroRemoval(model: BuiltInModel) {
    confirm("Remove ${model.family}'s voices from this phone? You can download them again later.", "Remove") {
        modelInstaller(model).remove()
        scope.launch {
            val settings = repository.ttsSettings.get()
            if (settings.enginePackage == model.engineId) {
                persistTtsSettings(settings.copy(enginePackage = null, voiceIdentifier = null))
                toast("${model.family} removed. Speaking with the phone's engine again.")
            }
            showTtsSettings()
        }
    }
}
