package com.vinicius741.webnovelarchiver.feature.settings

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.setupSection
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServerStatus
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelInstaller
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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

/** The Listening page's reads and actions: settings, engines, narration, the built-in models and the speed test. */
private class HostTtsSettingsData(
    private val host: ScreenHost,
) : TtsSettingsData {
    private val app get() = host.app
    private val container get() = host.app.appContainer

    override fun settings(): TtsSettings = host.repository.ttsSettings.get()

    override suspend fun persist(settings: TtsSettings) {
        host.repository.ttsSettings.save(settings)
        host.ttsEngine.applySettings(host.repository.ttsSettings.get())
    }

    override fun engines(): List<TtsEngineInfo> = TtsEngineCatalog.choices(app)

    override fun phoneEngine(): String? = TtsEngineCatalog.phoneDefault(app)

    // A built-in model lists its voices even before it is downloaded, so a voice can be chosen early.
    override fun voiceEngine(enginePackage: String?): String? =
        enginePackage?.takeIf(KokoroVoices::isEngine)
            ?: TtsEnginePlanning.resolve(enginePackage, TtsEngineCatalog.usablePackages(app, container.builtIns.installedEngines))

    override suspend fun voices(engine: String?): List<VoiceInfo>? = TtsEngineCatalog.voices(app, engine)

    override val narration: StateFlow<NarrationQueueDocument> get() = host.narration.queueState
    override val serverStatus: StateFlow<Map<String, NarrationServerStatus>> get() = host.narration.serverStatus

    override suspend fun refreshServerStatus(maxAgeMs: Long) = host.narration.refreshServerStatus(maxAgeMs)

    override fun modelState(model: BuiltInModel): StateFlow<KokoroModelState> = installer(model).state

    override fun install(model: BuiltInModel) = installer(model).install()

    override fun cancelInstall(model: BuiltInModel) = installer(model).cancel()

    override fun removeModel(model: BuiltInModel) = installer(model).remove()

    override fun hasSpeedTest(model: BuiltInModel): Boolean = speedTestFile(app, model).isFile

    override suspend fun runSpeedTest(
        model: BuiltInModel,
        settings: TtsSettings,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): String = runKokoroSpeedTest(host, model, settings, shouldStop, onProgress)

    override suspend fun lastSpeedTest(model: BuiltInModel): String? =
        withContext(Dispatchers.IO) { runCatching { speedTestFile(app, model).readText() }.getOrNull() }

    private fun installer(model: BuiltInModel): KokoroModelInstaller = container.modelInstallers.getValue(model)
}

/** The Listening page's model, kept while it is in the history. */
private fun ScreenHost.ttsSettingsModel(): TtsSettingsScreenModel =
    screenModels.get(AppRoute.TtsSettings.stableKey) {
        // The process scope: a change saved on leaving must finish even if the activity goes away.
        TtsSettingsScreenModel(HostTtsSettingsData(this), app.appContainer.applicationScope)
    }

/**
 * The Listening settings page (v3): the voice (engine, voice, Google's pitch), a sample right under it to
 * try the voice, the built-in models, where chapter audio generates and how far ahead, behavior, and pauses. Everything saves
 * as it changes; playback speed lives on the player. Built from [TtsSettingsScreenModel]'s state: a new
 * engine or voice, a computer added, removed or chosen, or a model card coming or going rebuilds the page;
 * download progress and the computers' online checks update it in place, and the computers are checked
 * only while the page is on screen.
 */
internal fun ScreenHost.showTtsSettings() {
    val host = this
    screen(route = AppRoute.TtsSettings, title = "Listening", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = ttsSettingsModel()
        val state = model.refresh()
        val saved = state.settings
        val current: () -> TtsSettings = { model.state.value.settings }
        capSettingsWidth(this)
        section("Voice")
        settingsGroup {
            valueRow(
                "Engine",
                TtsEnginePlanning.label(saved.enginePackage, state.engines, state.phoneEngine),
                R.drawable.wna_speaker,
            ) { showTtsEnginePicker(model) }
            valueRow(
                "Voice",
                ttsVoiceLabel(saved.voiceIdentifier, saved.enginePackage),
                R.drawable.wna_record_voice,
            ) { showTtsVoicePicker(model) }
            // Only Google's engine has a pitch control that does something.
            if (TtsEnginePlanning.hasPitch(saved.enginePackage ?: state.phoneEngine)) {
                groupStepper("Pitch", null, saved.pitch.toDouble(), PITCH_SPEC) { value ->
                    model.update { it.copy(pitch = value.toFloat()) }
                    null
                }
            }
        }
        // The sample sits right under the voice so a new voice can be tried at once.
        section("Try it")
        addView(ttsSampleCard(model, state))
        val cards =
            state.modelCards.map { builtIn ->
                KokoroModelCard(host, model, builtIn).also { card ->
                    addView(card.view)
                    (card.view.layoutParams as LinearLayout.LayoutParams).topMargin = dp(Spacing.MD)
                    card.show(state)
                }
            }
        val serverRows = setupSection(host, state.narration)
        serverRows.bind(state.serverStatus)
        section("Behavior")
        settingsGroup {
            switchRow(
                "Replay a little after long breaks",
                checked = saved.contextReplay,
            ) { on -> model.update { it.copy(contextReplay = on) } }
            switchRow("Say chapter titles", checked = saved.announceChapters) { on -> model.update { it.copy(announceChapters = on) } }
            switchRow(
                "Headphone and lock-screen skips move whole chapters",
                "Off: they skip a short way within the chapter",
                saved.chapterSkipButtons,
            ) { on -> model.update { it.copy(chapterSkipButtons = on) } }
        }
        section("Pauses")
        addPausesGroup(this, current) { change -> model.update(change) }
        bindWhileAttached(scope, model.state) { next ->
            if (next.buildKey != state.buildKey) {
                showTtsSettings()
                return@bindWhileAttached
            }
            serverRows.bind(next.serverStatus)
            cards.forEach { it.show(next) }
        }
        // Online checks for the computers while the page is visible and the app is in the foreground.
        bindWhileAttached(scope, model.serverChecks { app.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }) {}
        // Leaving the page saves a change still waiting for the stepper to settle.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) = model.flush()
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
    container.settingsNote("Pauses last this long at any speed. Playback speed changes only the gaps between words.")
}

private const val PHRASE_STEP_MS = 25.0
private const val LONG_STEP_MS = 50.0
