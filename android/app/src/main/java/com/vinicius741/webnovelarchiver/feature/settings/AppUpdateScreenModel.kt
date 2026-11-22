package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The update checker as App updates sees it ([com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator]). */
internal interface AppUpdateData {
    val updates: StateFlow<AppUpdateState>
    val installedVersionName: String

    /** Only the release app installs updates. */
    val releaseBuild: Boolean

    /** False in the offline simulation. */
    val checksEnabled: Boolean
    var autoCheckEnabled: Boolean
    val skippedTag: String?

    /** When GitHub was last asked, 0 when never. */
    val lastCheckedAtMillis: Long

    fun checkNow()

    fun now(): Long
}

internal data class AppUpdateUiState(
    val update: AppUpdateState,
    val installedVersionName: String,
    val releaseBuild: Boolean,
    val checksEnabled: Boolean,
    val autoCheckEnabled: Boolean,
    val skippedTag: String?,
    val lastCheckedAtMillis: Long,
    /** When the state was read; the "Checked … ago" line is relative to it. */
    val readAtMillis: Long,
) {
    /** What the page is built from; the switch flips itself, so its value is left out. */
    val buildKey: Any get() = copy(autoCheckEnabled = false)
}

/**
 * App updates (`AppRoute.AppUpdate`): the installed version, the checker's state (followed while a
 * renderer collects [state]), and the automatic-check option.
 */
internal class AppUpdateScreenModel(
    private val data: AppUpdateData,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(read(data.updates.value))
    val state: StateFlow<AppUpdateUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (observed) {
                        data.updates.collect { update ->
                            if (update !=
                                mutableState.value.update
                            ) {
                                mutableState.value = read(update)
                            }
                        }
                    }
                }
        }
    }

    /** Re-reads everything (returning from the browser: the "checked" time and state may have moved). */
    fun refresh(): AppUpdateUiState = read(data.updates.value).also { mutableState.value = it }

    fun checkNow() = data.checkNow()

    fun setAutoCheck(on: Boolean) {
        data.autoCheckEnabled = on
        mutableState.update { it.copy(autoCheckEnabled = on) }
    }

    private fun read(update: AppUpdateState) =
        AppUpdateUiState(
            update = update,
            installedVersionName = data.installedVersionName,
            releaseBuild = data.releaseBuild,
            checksEnabled = data.checksEnabled,
            autoCheckEnabled = data.autoCheckEnabled,
            skippedTag = data.skippedTag,
            lastCheckedAtMillis = data.lastCheckedAtMillis,
            readAtMillis = data.now(),
        )
}
