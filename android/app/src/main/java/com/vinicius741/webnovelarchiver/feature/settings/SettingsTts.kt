package com.vinicius741.webnovelarchiver.feature.settings

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.narration.ServerRows
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.setupSection
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the Listening page shows but has not finished saving (a held stepper saves once it settles).
 * Cleared once that save finishes, so a quick re-render never shows older values.
 */
private var unsavedTts: TtsSettings? = null

private val PITCH_SPEC =
    StepperSpec(
        step = 0.05,
        min = SettingsValidation.TTS_MIN.toDouble(),
        max = SettingsValidation.TTS_MAX.toDouble(),
        unit = "×",
        decimals = 2,
        label = "Pitch",
    )

private fun pauseSpec(
    label: String,
    step: Double,
    maxMs: Long,
    zeroLabel: String = "None",
) = StepperSpec(step = step, min = 0.0, max = maxMs.toDouble(), unit = " ms", zeroLabel = zeroLabel, label = label)

/**
 * The Listening settings page (v3): the voice (engine, voice, Google's pitch, built-in models),
 * where chapter audio generates and how far ahead, behavior, pauses, and a sample. Everything saves
 * as it changes; playback speed lives on the player.
 */
internal fun ScreenHost.showTtsSettings() {
    rerender = { showTtsSettings() }
    val host = this
    val saved = unsavedTts ?: repository.ttsSettings.get()
    val engines = TtsEngineCatalog.choices(app)
    val phoneEngine = TtsEngineCatalog.phoneDefault(app)
    val engineLabel = TtsEnginePlanning.label(saved.enginePackage, engines, phoneEngine)
    val current: () -> TtsSettings = { unsavedTts ?: repository.ttsSettings.get() }
    val handler = Handler(Looper.getMainLooper())
    val save =
        Runnable {
            val toSave = unsavedTts ?: return@Runnable
            scope.launch {
                try {
                    persistTtsSettings(toSave)
                } finally {
                    if (unsavedTts === toSave) unsavedTts = null
                }
            }
        }

    fun update(change: (TtsSettings) -> TtsSettings) {
        unsavedTts = change(current())
        handler.removeCallbacks(save)
        handler.postDelayed(save, SAVE_DELAY_MS)
    }
    val document = narration.queueState.value
    var serverRows: ServerRows? = null
    screen(route = AppRoute.TtsSettings, title = "Listening", scrollable = true) {
        capSettingsWidth(this)
        section("Voice")
        settingsGroup {
            valueRow("Engine", engineLabel, R.drawable.wna_speaker) { showTtsEnginePicker(engines, phoneEngine, current) }
            valueRow(
                "Voice",
                ttsVoiceLabel(saved.voiceIdentifier, saved.enginePackage),
                R.drawable.wna_record_voice,
            ) { showTtsVoicePicker(current) }
            // Only Google's engine has a pitch control that does something.
            if (TtsEnginePlanning.hasPitch(saved.enginePackage)) {
                groupStepper("Pitch", null, saved.pitch.toDouble(), PITCH_SPEC) { value ->
                    update { it.copy(pitch = value.toFloat()) }
                    null
                }
            }
        }
        modelCards(saved).forEach { model ->
            addView(
                kokoroModelCard(model).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Spacing.MD)
                },
            )
        }
        serverRows = setupSection(host, document)
        section("Behavior")
        settingsGroup {
            switchRow(
                "Replay a little after long breaks",
                checked = saved.contextReplay,
            ) { on -> update { it.copy(contextReplay = on) } }
            switchRow("Say chapter titles", checked = saved.announceChapters) { on -> update { it.copy(announceChapters = on) } }
            switchRow(
                "Headphone and lock-screen skips move whole chapters",
                "Off: they skip a short way within the chapter",
                saved.chapterSkipButtons,
            ) { on -> update { it.copy(chapterSkipButtons = on) } }
        }
        section("Pauses")
        addPausesGroup(this, current) { change -> update(change) }
        section("Try it")
        addView(ttsSampleCard(current))
        // Leaving the page saves a change still waiting for the stepper to settle.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    handler.removeCallbacks(save)
                    save.run()
                }
            },
        )
    }
    screenObserver =
        scope.launch {
            launch { narration.serverStatus.collect { statuses -> serverRows?.bind(statuses) } }
            // Online checks for the computers while the page is visible.
            if (document.servers.isNotEmpty()) {
                launch {
                    app.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            narration.refreshServerStatus(SERVER_CHECK_INTERVAL_MS)
                            delay(SERVER_CHECK_TICK_MS)
                        }
                    }
                }
            }
            // A computer added, removed or chosen rebuilds the page; keep-ahead shows on its stepper.
            narration.queueState
                .map { Triple(it.servers, it.generateOnIds, it.phoneFallback) }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (navigator.current == AppRoute.TtsSettings) showTtsSettings() }
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
    container.settingsNote("Pauses last this long at any speed. Playback speed changes only the gaps between words.")
}

private const val PHRASE_STEP_MS = 25.0
private const val LONG_STEP_MS = 50.0
private const val SAVE_DELAY_MS = 400L
private const val SERVER_CHECK_INTERVAL_MS = 30_000L
private const val SERVER_CHECK_TICK_MS = 5_000L
