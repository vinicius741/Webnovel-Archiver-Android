package com.vinicius741.webnovelarchiver.navigation

import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Runs one fallible UI-triggered operation (R12): expected storage/network failures become a
 * visible message instead of an unhandled coroutine exception, cancellation propagates, and busy
 * state is cleared in `finally`. Not for ViewModel-style blanket catches — only for button-level
 * operations the user can retry.
 */
internal inline fun ScreenHost.runUiOperation(
    operation: String,
    noinline onExpectedError: (Throwable) -> Unit = { error -> toast(error.message ?: "Operation failed") },
    crossinline block: suspend () -> Unit,
) {
    scope.launch {
        runCatching { block() }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.w(error, "UI operation failed: %s", operation)
            onExpectedError(error)
        }
    }
}
