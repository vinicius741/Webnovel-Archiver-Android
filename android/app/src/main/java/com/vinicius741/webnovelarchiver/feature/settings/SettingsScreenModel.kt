package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The hub values that need PackageManager, WebView cookie or disk work, so they load off the main thread. */
internal data class HubSummaries(
    val voice: String,
    val patreon: String,
    val ai: String,
)

/** "On this phone": novels, chapters offline, generated audio, and the newest backup file. */
internal data class PhoneSummary(
    val novels: Int,
    val chapters: Int,
    val audioBytes: Long,
    val lastBackupAt: Long?,
    val lastBackupBytes: Long?,
)

/** What the Settings hub reads and changes. The slow reads ([summaries], [phoneSummary], [audioBytes]) run off the main thread. */
internal interface SettingsHubData {
    suspend fun summaries(): HubSummaries

    suspend fun phoneSummary(): PhoneSummary

    /** Generated audio on this phone, in bytes. */
    suspend fun audioBytes(): Long

    /** The background compression of older audio. */
    val compression: Flow<NarrationCompressionStatus>

    /** The hub's quick values, read from cached state on each visit. */
    fun rows(): SettingsHubRows

    suspend fun saveScreenLayoutMode(mode: String)
}

/** The hub rows' current values that come from cached state. */
internal data class SettingsHubRows(
    val cleanup: String,
    val tabs: String,
    val downloads: String,
    /** The saved "Large screen layout" mode (auto, cover or inner). */
    val screenLayoutMode: String,
    /** Why notifications are off, or null when Android allows them. */
    val notificationsOff: String?,
    val storageNeedsAttention: Boolean,
    val appUpdate: String,
    val appUpdateNeedsAttention: Boolean,
)

/** The last values the hub loaded, shown at once on the next visit while fresh ones load. */
internal class SettingsHubCache {
    var summaries: HubSummaries? = null
    var phone: PhoneSummary? = null
}

internal data class SettingsHubUiState(
    val rows: SettingsHubRows,
    /** Null until loaded the first time. */
    val summaries: HubSummaries?,
    val phone: PhoneSummary?,
    /** The compression of older audio; null until the first status. */
    val compression: NarrationCompressionStatus?,
    /** The audio total measured with the latest compression status. */
    val audioBytes: Long? = null,
) {
    /** What the page is built from; the "On this phone" card follows the rest in place. */
    val buildKey: Any get() = rows to summaries
}

/**
 * The Settings hub (`AppRoute.Settings`): each row's current value, the slow summaries loaded off the
 * main thread, and the "On this phone" card, which follows the audio compression while a renderer
 * collects [state].
 */
internal class SettingsScreenModel(
    private val data: SettingsHubData,
    private val cache: SettingsHubCache,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(SettingsHubUiState(data.rows(), cache.summaries, cache.phone, null))
    val state: StateFlow<SettingsHubUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) followCompression() }
        }
    }

    /** Re-reads the rows (a setting may have changed elsewhere, or notifications in Android) and reloads the slow values. */
    fun refresh() {
        mutableState.update { it.copy(rows = data.rows()) }
        scope.launch {
            val summaries = data.summaries()
            cache.summaries = summaries
            mutableState.update { it.copy(summaries = summaries) }
        }
        scope.launch {
            val phone = data.phoneSummary()
            cache.phone = phone
            mutableState.update { it.copy(phone = phone) }
        }
    }

    fun setScreenLayoutMode(mode: String) {
        scope.launch {
            data.saveScreenLayoutMode(mode)
            mutableState.update { it.copy(rows = data.rows()) }
        }
    }

    /** Each converted chapter shrinks the audio: the stat and the status line follow it live. */
    private suspend fun followCompression() {
        data.compression.collect { status ->
            val total = data.audioBytes()
            val phone = mutableState.value.phone?.copy(audioBytes = total)
            if (phone != null) cache.phone = phone
            mutableState.update { it.copy(phone = phone ?: it.phone, compression = status, audioBytes = total) }
        }
    }
}
