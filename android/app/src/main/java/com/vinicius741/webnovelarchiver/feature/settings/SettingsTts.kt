package com.vinicius741.webnovelarchiver.feature.settings

import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.clearTtsSession
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * What Voice & Speech shows but has not saved. It outlives a fold re-render; opening the screen
 * again starts from the saved settings. Engine and voice picks save straight away (with the draft).
 */
private var ttsDraft: TtsSettings? = null

private val RATE_SPEC =
    StepperSpec(
        step = 0.05,
        min = SettingsValidation.TTS_MIN.toDouble(),
        max = SettingsValidation.TTS_MAX.toDouble(),
        unit = "×",
        decimals = 2,
        label = "Rate",
    )
private val PITCH_SPEC = RATE_SPEC.copy(label = "Pitch")

private fun pauseSpec(
    label: String,
    step: Double,
    maxMs: Long,
    zeroLabel: String = "None",
) = StepperSpec(step = step, min = 0.0, max = maxMs.toDouble(), unit = " ms", zeroLabel = zeroLabel, label = label)

/** Voice & Speech settings (v3), reached from Settings, Listen, and the reader's settings sheet. */
internal fun ScreenHost.showTtsSettings(keepDraft: Boolean = false) {
    rerender = { showTtsSettings(keepDraft = true) }
    val saved = repository.ttsSettings.get()
    if (!keepDraft || ttsDraft == null) ttsDraft = saved
    val engines = TtsEngineCatalog.choices(app)
    val phoneEngine = TtsEngineCatalog.phoneDefault(app)
    val engineLabel = TtsEnginePlanning.label(saved.enginePackage, engines, phoneEngine)
    val current: () -> TtsSettings = { ttsDraft ?: repository.ttsSettings.get() }
    lateinit var bar: DockedSaveBar

    fun update(change: (TtsSettings) -> TtsSettings) {
        ttsDraft = change(current())
        bar.show(current() != repository.ttsSettings.get())
    }
    bar =
        DockedSaveBar(app, "Save TTS") {
            val toSave = current()
            scope.launch {
                persistTtsSettings(toSave)
                ttsDraft = null
                toast("Voice settings saved")
                showTtsSettings()
            }
        }
    val subtitle = "${engineLabel.removeSuffix(" (built in)")} · ${ttsVoiceLabel(saved.voiceIdentifier).substringBefore(" (")}"
    screen(route = AppRoute.TtsSettings, title = "Voice & Speech", subtitle = subtitle, fullWidthBody = true) {
        scrollWithDockedBar(this, bar) {
            addSavedSessionCard(this)
            section("Profile")
            addView(ttsProfileCard(saved, current))
            section("Voice")
            settingsGroup {
                valueRow("Engine", engineLabel, R.drawable.wna_speaker) { showTtsEnginePicker(engines, phoneEngine, current) }
                valueRow("Voice", ttsVoiceLabel(saved.voiceIdentifier), R.drawable.wna_record_voice) { showTtsVoicePicker(current) }
                groupStepper("Rate", "Speaking speed", current().rate.toDouble(), RATE_SPEC) { value ->
                    update { it.copy(rate = value.toFloat()) }
                    null
                }
                // Kokoro has no pitch control, so a stepper would do nothing.
                if (KokoroVoices.isEngine(saved.enginePackage)) {
                    infoRow("Pitch", "Not available for Kokoro voices")
                } else {
                    groupStepper("Pitch", null, current().pitch.toDouble(), PITCH_SPEC) { value ->
                        update { it.copy(pitch = value.toFloat()) }
                        null
                    }
                }
            }
            if (showsKokoroCard(saved)) {
                addView(
                    kokoroModelCard().apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Spacing.MD)
                    },
                )
            }
            section("Behavior")
            settingsGroup {
                switchRow(
                    "Replay a little after long breaks",
                    checked = current().contextReplay,
                ) { on -> update { it.copy(contextReplay = on) } }
                switchRow("Say chapter titles", checked = current().announceChapters) { on -> update { it.copy(announceChapters = on) } }
                switchRow(
                    "Headphone and lock-screen skips move whole chapters",
                    "Off: they skip a short way within the chapter",
                    current().chapterSkipButtons,
                ) { on -> update { it.copy(chapterSkipButtons = on) } }
            }
            section("Pauses")
            addPausesGroup(this, current) { change -> update(change) }
            section("Try it")
            addView(ttsSampleCard(current))
        }
        bar.show(current() != saved)
    }
}

private fun ScreenHost.addSavedSessionCard(container: LinearLayout) {
    val session = repository.getTtsSession() ?: return
    val colors = ThemeManager.colors
    container.tintedCard(colors.primary) {
        addView(makeEyebrow(context, "Saved TTS session"))
        addView(
            makeText(
                context,
                "${session.chapterTitle}  ·  sentence ${session.currentChunkIndex + 1}",
                Type.TITLE_SMALL,
                colors.onSurface,
            ).apply {
                textSize = TITLE_SP
            },
        )
        if (session.storyTitle.isNotBlank()) {
            addView(makeText(context, session.storyTitle, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    makeButton(context, "Resume TTS", Btn.FILLED, R.drawable.wna_play) {
                        TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
                    },
                )
                addView(
                    makeButton(context, "Clear session", Btn.TEXT) {
                        scope.launch {
                            repository.clearTtsSession()
                            showTtsSettings(keepDraft = true)
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart =
                            dp(Spacing.SM)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.MD)
            },
        )
    }
}

/** Real-time pauses: each lasts this long at any playback speed. */
private fun addPausesGroup(
    container: LinearLayout,
    current: () -> TtsSettings,
    update: ((TtsSettings) -> TtsSettings) -> Unit,
) {
    container.settingsGroup {
        groupStepper(
            "Commas and phrases",
            "Extra pause",
            current().phrasePauseMs.toDouble(),
            pauseSpec("Commas and phrases", PHRASE_STEP_MS, TtsSettings.MAX_PHRASE_PAUSE_MS, zeroLabel = "Off"),
        ) { value ->
            update { it.copy(phrasePauseMs = value.toLong()) }
            null
        }
        groupStepper(
            "Between sentences",
            null,
            current().sentencePauseMs.toDouble(),
            pauseSpec("Between sentences", PHRASE_STEP_MS, TtsSettings.MAX_SENTENCE_PAUSE_MS),
        ) { value ->
            update { it.copy(sentencePauseMs = value.toLong()) }
            null
        }
        groupStepper(
            "Between paragraphs",
            null,
            current().paragraphPauseMs.toDouble(),
            pauseSpec("Between paragraphs", LONG_STEP_MS, TtsSettings.MAX_PARAGRAPH_PAUSE_MS),
        ) { value ->
            update { it.copy(paragraphPauseMs = value.toLong()) }
            null
        }
        groupStepper(
            "At scene breaks",
            null,
            current().scenePauseMs.toDouble(),
            pauseSpec("At scene breaks", LONG_STEP_MS, TtsSettings.MAX_SCENE_PAUSE_MS),
        ) { value ->
            update { it.copy(scenePauseMs = value.toLong()) }
            null
        }
    }
    container.settingsNote("Pauses last this long at any speed. Only Rate changes the gaps between words.")
}

private const val TITLE_SP = 15f
private const val PHRASE_STEP_MS = 25.0
private const val LONG_STEP_MS = 50.0
