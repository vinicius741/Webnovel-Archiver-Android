package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes one OkHttp call with coroutine-cancellation bound to the underlying call (R13): when
 * the calling coroutine is cancelled, [Call.cancel] fires so a dead operation stops occupying an
 * I/O worker and the socket. Mirrors the AI client's OpenRouter cancellation pattern.
 */
internal suspend fun Call.executeCancellable(): Response =
    suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            if (resumed.compareAndSet(false, true)) cancel()
        }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (resumed.compareAndSet(false, true)) {
                        // onCancellation runs when the coroutine was cancelled after the response
                        // arrived — resumeWith would silently drop the value and leak the body.
                        continuation.resume(response) {
                            response.close()
                            call.cancel()
                        }
                    } else {
                        response.close()
                    }
                }
            },
        )
    }
