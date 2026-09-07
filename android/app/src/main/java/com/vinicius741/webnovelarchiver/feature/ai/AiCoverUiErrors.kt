package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Keep storage failures recoverable without swallowing lifecycle cancellation. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> ScreenHost.coverUiAttempt(action: suspend () -> T): T? =
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.w(error, "Could not load or save AI cover work")
        toast("Could not load or save cover work. Check available storage and try again.")
        null
    }
