package com.vinicius741.webnovelarchiver.navigation

import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Runs one fallible UI-triggered operation (R12): expected storage/network failures become a
 * visible message instead of an unhandled coroutine exception, cancellation propagates, and
 * fatal [Error]s (OOM, linkage) are never swallowed. Not for ViewModel-style blanket catches —
 * only for button-level operations the user can retry.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun ScreenHost.runUiOperation(
    operation: String,
    noinline onExpectedError: (Throwable) -> Unit = { error -> toast(error.message ?: "Operation failed") },
    crossinline block: suspend () -> Unit,
) {
    scope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "UI operation failed: %s", operation)
            onExpectedError(error)
        }
    }
}
