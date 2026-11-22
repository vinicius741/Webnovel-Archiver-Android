package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.TtsSamplePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What the Listening page reads and drives: speech settings, engines and voices, built-in models, the computers, and the speed test. */
internal interface TtsSettingsData {
    fun settings(): TtsSettings

    /** Saves [settings] and hands them to the loaded chapter, which picks up a new voice in place. */
    suspend fun persist(settings: TtsSettings)

    fun engines(): List<TtsEngineInfo>

    /** The engine chosen in the phone's text-to-speech settings, when Android says. */
    fun phoneEngine(): String?

    /** The engine whose voices the picker lists for [enginePackage]: a built-in model as is, else one that can speak now. */
    fun voiceEngine(enginePackage: String?): String?

    /** The voices [engine] offers; null when the engine did not start. */
    suspend fun voices(engine: String?): List<VoiceInfo>?

    val narration: StateFlow<NarrationQueueDocument>
    val serverStatus: StateFlow<Map<String, NarrationServerStatus>>

    /** Re-checks the computers whose status is older than [maxAgeMs]. */
    suspend fun refreshServerStatus(maxAgeMs: Long)

    fun modelState(model: BuiltInModel): StateFlow<KokoroModelState>

    fun install(model: BuiltInModel)

    fun cancelInstall(model: BuiltInModel)

    fun removeModel(model: BuiltInModel)

    fun hasSpeedTest(model: BuiltInModel): Boolean

    /** Runs the speed test of [model] (narration stops meanwhile) and saves its report; returns the report. */
    suspend fun runSpeedTest(
        model: BuiltInModel,
        settings: TtsSettings,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): String

    suspend fun lastSpeedTest(model: BuiltInModel): String?
}

/** A speed test in progress, or its report once it finished (until shown). */
internal data class SpeedTestRun(
    val model: BuiltInModel,
    val message: String = "Starting",
    val stopping: Boolean = false,
    val report: String? = null,
)

internal data class TtsSettingsUiState(
    /** The settings shown: the newest change, saved or not. */
    val settings: TtsSettings,
    val engines: List<TtsEngineInfo>,
    val phoneEngine: String?,
    val narration: NarrationQueueDocument,
    val serverStatus: Map<String, NarrationServerStatus>,
    val modelStates: Map<BuiltInModel, KokoroModelState>,
    /** Models with a finished speed test to show again. */
    val speedTests: Set<BuiltInModel>,
) {
    /** The built-in models with a card: the chosen one first, then any installed or installing (so it can be removed). */
    val modelCards: List<BuiltInModel>
        get() {
            val chosen = KokoroVoices.model(settings.enginePackage)
            val others = BuiltInModel.entries.filter { it != chosen && modelStates[it] != KokoroModelState.NotInstalled() }
            return listOfNotNull(chosen) + others
        }

    fun installed(model: BuiltInModel): Boolean = modelStates[model] is KokoroModelState.Installed

    /**
     * What the page is built from: the engine and voice, the computers (added, removed or chosen),
     * the model cards and speed tests. A stepper or switch change, a download's progress or a
     * computer's online check updates the page in place.
     */
    val buildKey: Any
        get() =
            listOf(
                settings.enginePackage,
                settings.voiceIdentifier,
                narration.servers,
                narration.generateOnIds,
                narration.phoneFallback,
                modelCards,
                speedTests,
            )
}

/**
 * The Listening settings page (`AppRoute.TtsSettings`): the speech settings, saved as they change
 * (debounced, in the process scope; [UnsavedSettings.tts] lets a quick return show a change still
 * saving), the engine and voice, the built-in models' downloads, the computers and their online
 * status, the sample's text and computers, and the debug speed test. It follows narration, the
 * computers' status and the model downloads while a renderer collects [state].
 */
internal class TtsSettingsScreenModel(
    private val data: TtsSettingsData,
    saveScope: CoroutineScope,
    private val unsaved: UnsavedSetting<TtsSettings> = UnsavedSettings.tts,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val autosave = Autosave(unsaved, scope, saveScope, SAVE_DELAY_MS) { data.persist(it) }
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<TtsSettingsUiState> = mutableState.asStateFlow()

    /** The sample text being edited; null is the default text. */
    var sampleText: String? = null
        private set

    /** The computers a sample is generated on; null follows Listen's Generate on. */
    private var sampleComputerIds: Set<String>? = null
    private val stopSpeedTest = AtomicBoolean(false)
    private val mutableSpeedTest = MutableStateFlow<SpeedTestRun?>(null)
    val speedTest: StateFlow<SpeedTestRun?> = mutableSpeedTest.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) follow() }
        }
    }

    /** Re-reads everything (keeping a change not saved yet) and returns the state. */
    fun refresh(): TtsSettingsUiState = read().also { mutableState.value = it }

    /** Applies a stepper or switch change, saved once changes settle. */
    fun update(change: (TtsSettings) -> TtsSettings) {
        val next = change(state.value.settings)
        mutableState.update { it.copy(settings = next) }
        autosave.change(next)
    }

    /** Speaks with [enginePackage] (null: the phone's engine); voices differ per engine, so the voice resets. Returns what to tell the user. */
    fun chooseEngine(enginePackage: String?): String {
        saveNow { it.copy(enginePackage = enginePackage, voiceIdentifier = null) }
        val model = KokoroVoices.model(enginePackage)
        // A built-in model needs no download: chapters generate on the computers.
        return if (model != null && !state.value.installed(model)) {
            "Engine changed. Chapters generate on your computers until you download it here."
        } else {
            "Engine changed. Pick a voice from it, or keep its default."
        }
    }

    fun chooseVoice(voice: VoiceInfo?) = saveNow { it.copy(voiceIdentifier = voice?.identifier) }

    /** The engine whose voices the picker lists. */
    fun voiceEngine(): String? = data.voiceEngine(state.value.settings.enginePackage)

    suspend fun voices(engine: String?): List<VoiceInfo>? = data.voices(engine)

    fun installModel(model: BuiltInModel) = data.install(model)

    fun cancelInstall(model: BuiltInModel) = data.cancelInstall(model)

    /** Removes [model]'s voices; speaking with it falls back to the phone's engine. Returns what to tell the user, if anything. */
    fun removeModel(model: BuiltInModel): String? {
        data.removeModel(model)
        if (state.value.settings.enginePackage != model.engineId) return null
        saveNow { it.copy(enginePackage = null, voiceIdentifier = null) }
        return "${model.family} removed. Speaking with the phone's engine again."
    }

    fun setSampleText(text: String?) {
        sampleText = text
    }

    fun chosenSampleComputers(): List<NarrationServer> {
        val narration = state.value.narration
        return TtsSamplePlanning.chosenComputers(narration.servers, sampleComputerIds, narration.generateOnIds)
    }

    fun toggleSampleComputer(serverId: String) {
        sampleComputerIds = TtsSamplePlanning.toggle(chosenSampleComputers(), serverId)
    }

    /** Re-checks the computers every few seconds while collected (the page is on screen) and [foreground] says the app is in front. */
    fun serverChecks(foreground: () -> Boolean): Flow<Unit> =
        flow {
            if (state.value.narration.servers
                    .isEmpty()
            ) {
                return@flow
            }
            while (true) {
                if (foreground()) data.refreshServerStatus(SERVER_CHECK_INTERVAL_MS)
                emit(Unit)
                delay(SERVER_CHECK_TICK_MS)
            }
        }

    /** Starts the speed test of [model] unless one runs. */
    fun startSpeedTest(model: BuiltInModel) {
        if (mutableSpeedTest.value != null) return
        stopSpeedTest.set(false)
        mutableSpeedTest.value = SpeedTestRun(model)
        scope.launch {
            val report =
                data.runSpeedTest(model, data.settings(), stopSpeedTest::get) { progress ->
                    mutableSpeedTest.update { run -> if (run == null || run.stopping) run else run.copy(message = progress) }
                }
            mutableSpeedTest.update { it?.copy(report = report) }
            mutableState.update { it.copy(speedTests = speedTests()) }
        }
    }

    /** Stops the running speed test after the current sentence. */
    fun stopSpeedTest() {
        stopSpeedTest.set(true)
        mutableSpeedTest.update { it?.copy(stopping = true, message = "Stopping after the current sentence") }
    }

    /** The finished report was shown. */
    fun speedTestShown() {
        mutableSpeedTest.value = null
    }

    suspend fun lastSpeedTest(model: BuiltInModel): String? = data.lastSpeedTest(model)

    /** Saves a change still waiting to settle (on leaving the page). */
    fun flush() = autosave.flush()

    override fun onCleared() = autosave.flush()

    private fun saveNow(change: (TtsSettings) -> TtsSettings) {
        update(change)
        autosave.flush()
    }

    private suspend fun follow() {
        coroutineScope {
            launch { data.narration.collect { document -> mutableState.update { it.copy(narration = document) } } }
            launch { data.serverStatus.collect { statuses -> mutableState.update { it.copy(serverStatus = statuses) } } }
            combine(BuiltInModel.entries.map { model -> data.modelState(model).map { model to it } }) { it.toMap() }
                .collect { states -> mutableState.update { it.copy(modelStates = states) } }
        }
    }

    private fun read(): TtsSettingsUiState =
        TtsSettingsUiState(
            settings = unsaved.shown(data::settings),
            engines = data.engines(),
            phoneEngine = data.phoneEngine(),
            narration = data.narration.value,
            serverStatus = data.serverStatus.value,
            modelStates = BuiltInModel.entries.associateWith { data.modelState(it).value },
            speedTests = speedTests(),
        )

    private fun speedTests(): Set<BuiltInModel> = BuiltInModel.entries.filterTo(mutableSetOf(), data::hasSpeedTest)

    private companion object {
        const val SAVE_DELAY_MS = 400L
        const val SERVER_CHECK_INTERVAL_MS = 30_000L
        const val SERVER_CHECK_TICK_MS = 5_000L
    }
}
