package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.ai.AiUsagePeriod
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.ai.RecentAiRequestGroup
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsagePeriodSummary
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** What AI settings reads and saves. */
internal interface AiSettingsData {
    fun settings(): AiSettings

    suspend fun save(settings: AiSettings)

    /** The app's own log of AI requests. */
    fun ledger(): AiUsageLedger

    /** OpenRouter's spending figures for [apiKey] (a network call). */
    suspend fun keyUsage(apiKey: String): OpenRouterKeyUsage

    fun now(): Long
}

/** The spending card's figures from the app's own log, and the recent requests. */
internal data class AiSpending(
    val today: AiUsagePeriodSummary,
    val month: AiUsagePeriodSummary,
    val allTime: AiUsagePeriodSummary,
    val recent: List<RecentAiRequestGroup>,
)

internal data class AiSettingsUiState(
    val settings: AiSettings,
    val spending: AiSpending,
    /** OpenRouter's figures for the entered key, which replace the app's own once loaded. */
    val keyUsage: OpenRouterKeyUsage?,
    /** Bumped when a model pick is saved, so the model rows on screen re-read it. */
    val modelsRevision: Int,
)

/**
 * AI settings (`AppRoute.AiSettings`): the API key being typed (saved once typing pauses, in the
 * process scope; a quick return shows it through [UnsavedSettings.aiKey]), the model choices, the
 * chapter scan limit, and spending. The key and the stepper never rebuild the page; OpenRouter's
 * figures patch the spending card, and [modelsRevision][AiSettingsUiState.modelsRevision] refreshes
 * the model rows of whichever render is on screen (a picker may outlive a fold).
 */
internal class AiSettingsScreenModel(
    private val data: AiSettingsData,
    private val saveScope: CoroutineScope,
    private val unsavedKey: UnsavedSetting<String> = UnsavedSettings.aiKey,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val keySave =
        Autosave(unsavedKey, scope, saveScope, KEY_SAVE_DELAY_MS) { key ->
            val current = data.settings()
            val next = key.trim().ifBlank { null }
            if (current.apiKey != next) data.save(current.copy(apiKey = next))
        }
    private var usageFetch: Job? = null
    private val mutableState = MutableStateFlow(AiSettingsUiState(data.settings(), spending(), keyUsage = null, modelsRevision = 0))
    val state: StateFlow<AiSettingsUiState> = mutableState.asStateFlow()

    /** The key the field shows: one typed and not saved yet, else the saved one. */
    val apiKey: String get() =
        unsavedKey.value ?: state.value.settings.apiKey
            .orEmpty()

    /** Re-reads the settings and the log, and asks OpenRouter for the entered key's spending (on each render, as before). */
    fun refresh() {
        mutableState.update { it.copy(settings = data.settings(), spending = spending()) }
        val key = apiKey.trim()
        usageFetch?.cancel()
        if (key.isBlank()) {
            mutableState.update { it.copy(keyUsage = null) }
            return
        }
        usageFetch =
            scope.launch {
                val usage =
                    try {
                        data.keyUsage(key)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (
                        @Suppress("TooGenericExceptionCaught") error: Exception,
                    ) {
                        Timber.w(error, "OpenRouter key usage could not be loaded")
                        return@launch
                    }
                mutableState.update { it.copy(keyUsage = usage) }
            }
    }

    fun setApiKey(key: String) = keySave.change(key)

    fun setCoverEvidenceChapters(count: Int) = update { it.copy(coverEvidenceChapters = count) }

    /** Saves a model pick ([edit] applies it to the settings) and refreshes the rows on screen. */
    suspend fun saveModelChoice(edit: (AiSettings) -> AiSettings) {
        // Serialized with the API-key autosave so concurrent writes never clobber each other.
        unsavedKey.enqueueSave(saveScope) { data.save(edit(data.settings())) }.join()
        mutableState.update { it.copy(settings = data.settings()) }
        modelsChanged()
    }

    /** The model rows on screen re-read the models (a picker closed, or the catalog loaded). */
    fun modelsChanged() = mutableState.update { it.copy(modelsRevision = it.modelsRevision + 1) }

    /** Saves a key still waiting for the typing pause (on leaving the page). */
    fun flush() = keySave.flush()

    override fun onCleared() = keySave.flush()

    private fun update(edit: (AiSettings) -> AiSettings) {
        scope.launch {
            unsavedKey.enqueueSave(saveScope) { data.save(edit(data.settings())) }.join()
            mutableState.update { it.copy(settings = data.settings()) }
        }
    }

    private fun spending(): AiSpending {
        val ledger = data.ledger()
        val now = data.now()
        return AiSpending(
            today = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.TODAY, now),
            month = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.CURRENT_MONTH, now),
            allTime = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.ALL_TIME, now),
            recent = AiUsagePlanning.groupRecentRecords(ledger.recentRecords, MAX_RECENT_REQUESTS),
        )
    }

    private companion object {
        /** A typed key is saved once typing pauses this long (and when the page closes). */
        const val KEY_SAVE_DELAY_MS = 600L
        const val MAX_RECENT_REQUESTS = 8
    }
}
