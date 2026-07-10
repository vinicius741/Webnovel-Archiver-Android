package com.vinicius741.webnovelarchiver.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MaintenanceOperation {
    ExportJson,
    ExportCleanupRules,
    ExportFull,
    ImportJson,
    RestoreFull,
}

sealed interface MaintenanceState {
    data object Idle : MaintenanceState

    data class Running(
        val operation: MaintenanceOperation,
    ) : MaintenanceState
}

/**
 * Process-wide maintenance gate. The owner lock is AppStorage's existing transaction monitor, so
 * maintenance waits for an active durable write and blocks every new participating read/write until
 * the operation completes. State always returns to Idle, including validation failures and throws.
 */
class MaintenanceCoordinator {
    private val _state = MutableStateFlow<MaintenanceState>(MaintenanceState.Idle)
    val state: StateFlow<MaintenanceState> = _state.asStateFlow()

    fun <T> runExclusive(
        ownerLock: Any,
        operation: MaintenanceOperation,
        block: () -> T,
    ): T =
        synchronized(ownerLock) {
            check(_state.value == MaintenanceState.Idle) { "Another maintenance operation is already running" }
            _state.value = MaintenanceState.Running(operation)
            try {
                block()
            } finally {
                _state.value = MaintenanceState.Idle
            }
        }

    fun <T> withStorageAccess(
        ownerLock: Any,
        block: () -> T,
    ): T = synchronized(ownerLock) { block() }
}
