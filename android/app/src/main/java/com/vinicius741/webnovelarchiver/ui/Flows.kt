package com.vinicius741.webnovelarchiver.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** Emits the current wall-clock time immediately, then at [periodMillis] until its collector ends. */
fun tickerFlow(periodMillis: Long = 1_000L): Flow<Long> =
    flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(periodMillis)
        }
    }
