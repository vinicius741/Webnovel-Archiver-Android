package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Keeps cancellation bound to the call until the response body has been consumed and closed. */
internal suspend fun <T> Call.executeCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result = runCatching { response.use(read) }
                    continuation.resumeWith(result)
                }
            },
        )
    }
