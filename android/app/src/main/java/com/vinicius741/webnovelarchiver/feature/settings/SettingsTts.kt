package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.labeledField
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showTtsVoiceDialog
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * Voice & Speech settings. [onBack] defaults to the main Settings screen; pass a reader return when
 * opened from Reader Settings so Back does not strand the user on Settings.
 */
internal fun ScreenHost.showTtsSettings(onBack: (() -> Unit)? = null) {
    val backAction = onBack ?: { showSettings() }
    val ttsSettings = repository.getTtsSettings()
    screen(route = AppRoute.TtsSettings, title = "Voice & Speech", onBack = backAction, scrollable = true) {
        repository.getTtsSession()?.let { session ->
            addView(
                card {
                    text("Saved TTS session", Type.TITLE_SMALL)
                    text(
                        "${session.chapterTitle} (chunk ${session.currentChunkIndex + 1})",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    )
                    flow {
                        button("Resume TTS", Btn.TONAL, R.drawable.wna_play) {
                            TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
                        }
                        button("Clear Session", Btn.TEXT, R.drawable.wna_delete) {
                            scope.launch {
                                repository.clearTtsSession()
                                showTtsSettings(onBack)
                            }
                        }
                    }
                },
            )
        }
        val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val voiceLabel = ttsSettings.voiceIdentifier ?: "System default"
        row {
            addView(
                makeText(context, "Voice", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val voiceBtn = makeButton(context, voiceLabel, Btn.TEXT, R.drawable.wna_speaker) { showTtsVoicePicker(onBack) }
            addView(
                voiceBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(Space.MD)
                },
            )
        }
        fullButton("Save TTS", Btn.FILLED, R.drawable.wna_check, bottomMarginDp = Space.SM) {
            scope.launch {
                repository.saveTtsSettings(
                    ttsSettings.copy(
                        pitch = SettingsValidation.ttsScalar(pitch.text.toString(), ttsSettings.pitch),
                        rate = SettingsValidation.ttsScalar(rate.text.toString(), ttsSettings.rate),
                    ),
                )
                toast("TTS settings saved")
            }
        }
    }
}

internal fun ScreenHost.showTtsVoicePicker(onBack: (() -> Unit)? = null) {
    val voices = ttsEngine.availableVoices()
    if (voices.isEmpty()) {
        lateinit var listener: (List<VoiceInfo>) -> Unit
        listener = { loadedVoices ->
            ttsEngine.removeVoiceAvailabilityListener(listener)
            app.runOnUiThread {
                if (loadedVoices.isEmpty()) {
                    toast("No local TTS voices available yet")
                } else {
                    showTtsVoiceDialog(loadedVoices, repository.getTtsSettings().voiceIdentifier) { voice ->
                        val current = repository.getTtsSettings()
                        scope.launch {
                            repository.saveTtsSettings(current.copy(voiceIdentifier = voice?.identifier))
                            showTtsSettings(onBack)
                        }
                    }
                }
            }
        }
        ttsEngine.addVoiceAvailabilityListener(listener)
        toast("Loading local TTS voices")
        return
    }
    showTtsVoiceDialog(voices, repository.getTtsSettings().voiceIdentifier) { voice ->
        val current = repository.getTtsSettings()
        scope.launch {
            repository.saveTtsSettings(current.copy(voiceIdentifier = voice?.identifier))
            showTtsSettings(onBack)
        }
    }
}
