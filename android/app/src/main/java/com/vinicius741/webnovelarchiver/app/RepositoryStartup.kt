package com.vinicius741.webnovelarchiver.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process startup state exposed to Activities and services that need hydrated repository data. */
sealed interface RepositoryReadiness {
    data object Loading : RepositoryReadiness

    data object Ready : RepositoryReadiness

    data class Failed(
        val cause: Throwable,
    ) : RepositoryReadiness
}

/**
 * Runs migration/recovery/hydration once on the supplied process scope. The scope determines the
 * dispatcher, so [AppContainer] can keep the complete startup transaction on Dispatchers.IO while
 * callers suspend without blocking the Android main thread.
 */
internal class RepositoryStartup(
    private val initialize: suspend () -> Unit,
) {
    private val _readiness = MutableStateFlow<RepositoryReadiness>(RepositoryReadiness.Loading)
    val readiness: StateFlow<RepositoryReadiness> = _readiness.asStateFlow()

    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job =
        job ?: synchronized(this) {
            job ?: run {
                val launched =
                    scope.launch {
                        runCatching { initialize() }
                            .onSuccess { _readiness.value = RepositoryReadiness.Ready }
                            .onFailure { _readiness.value = RepositoryReadiness.Failed(it) }
                    }
                job = launched
                launched
            }
        }

    suspend fun awaitReady() {
        when (val state = readiness.first { it !is RepositoryReadiness.Loading }) {
            RepositoryReadiness.Ready -> Unit
            is RepositoryReadiness.Failed -> throw state.cause
            RepositoryReadiness.Loading -> error("Unreachable readiness state")
        }
    }
}
