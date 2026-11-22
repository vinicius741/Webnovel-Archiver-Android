package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The download settings Download settings reads and saves. */
internal interface DownloadSettingsData {
    fun settings(): AppSettings

    /** Each site's own pace, by source id. */
    fun overrides(): Map<String, SourceDownloadSettings>

    suspend fun saveSettings(settings: AppSettings)

    suspend fun saveOverrides(overrides: Map<String, SourceDownloadSettings>)
}

internal data class DownloadSettingsUiState(
    /** The settings shown: the newest change, saved or not. */
    val settings: AppSettings,
    val overrides: Map<String, SourceDownloadSettings>,
    /** Whether the Advanced group (exact delays, parallel sites) is open. */
    val advancedOpen: Boolean,
) {
    val range: PaceRange get() = settings.range()

    /** What the page is built from; a stepper or pace change patches the controls in place. */
    val buildKey: Any get() = advancedOpen to overrides
}

internal fun AppSettings.range(): PaceRange = PaceRange(downloadDelay, maxOf(downloadDelay, downloadDelayMax))

/**
 * Download settings (`AppRoute.DownloadSettings`): the default pace, parallel sites and exact delay
 * range, saved as they change (debounced, in the process scope, so the hub and a quick return show
 * the newest values through [UnsavedSettings.downloads]), and each site's own pace.
 */
internal class DownloadSettingsScreenModel(
    private val data: DownloadSettingsData,
    saveScope: CoroutineScope,
    private val unsaved: UnsavedSetting<AppSettings> = UnsavedSettings.downloads,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val autosave = Autosave(unsaved, scope, saveScope, SAVE_DEBOUNCE_MS) { data.saveSettings(it) }
    private val mutableState = MutableStateFlow(DownloadSettingsUiState(shown(), data.overrides(), advancedOpen = false))
    val state: StateFlow<DownloadSettingsUiState> = mutableState.asStateFlow()

    /** Re-reads the settings (keeping a change not saved yet) and returns the state. */
    fun refresh(): DownloadSettingsUiState {
        mutableState.update { it.copy(settings = shown(), overrides = data.overrides()) }
        return state.value
    }

    /** Picks a named pace (Gentle / Balanced / Fast) by its index in [DownloadPacePlanning.presets]. */
    fun selectPace(index: Int): PaceRange {
        val range = DownloadPacePlanning.presets[index].range
        change { it.copy(downloadDelay = range.minMs, downloadDelayMax = range.maxMs) }
        return range
    }

    fun setParallelSites(count: Int) = change { it.copy(maxParallelSources = count) }

    /** Sets the shortest delay (the longest follows it up); returns the resulting range. */
    fun setDelayMin(ms: Long): PaceRange = setRange(DownloadPacePlanning.withMin(state.value.range, ms))

    /** Sets the longest delay (the shortest follows it down); returns the resulting range. */
    fun setDelayMax(ms: Long): PaceRange = setRange(DownloadPacePlanning.withMax(state.value.range, ms))

    fun toggleAdvanced() = mutableState.update { it.copy(advancedOpen = !it.advancedOpen) }

    /** Saves [sourceId]'s pace ([range]), or removes it (null) so the site uses the default. */
    fun saveSourcePace(
        sourceId: String,
        range: PaceRange?,
    ) {
        scope.launch {
            withContext(NonCancellable) { data.saveOverrides(DownloadPacePlanning.withOverride(data.overrides(), sourceId, range)) }
            mutableState.update { it.copy(overrides = data.overrides()) }
        }
    }

    /** Saves a change still waiting for the debounce (on leaving the page). */
    fun flush() = autosave.flush()

    override fun onCleared() = autosave.flush()

    private fun setRange(range: PaceRange): PaceRange {
        change { it.copy(downloadDelay = range.minMs, downloadDelayMax = range.maxMs) }
        return range
    }

    private fun change(edit: (AppSettings) -> AppSettings) {
        val next = edit(state.value.settings)
        mutableState.update { it.copy(settings = next) }
        autosave.change(next)
    }

    private fun shown(): AppSettings = unsaved.shown(data::settings)

    private companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
