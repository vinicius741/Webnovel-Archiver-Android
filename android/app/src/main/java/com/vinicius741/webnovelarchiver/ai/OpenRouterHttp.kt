package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Executes one JSON request and binds coroutine cancellation to the underlying OkHttp call. */
internal suspend fun <T> OkHttpClient.executeOpenRouterJson(
    request: Request,
    parse: (JsonObject, Int) -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
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
                    response.use {
                        runCatching {
                            val json = runCatching { JsonParser.parseString(response.body.string()) }.getOrNull()
                            val root = json?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                            parse(root, response.code)
                        }.onSuccess { continuation.resumeWith(Result.success(it)) }
                            .onFailure { continuation.resumeWith(Result.failure(it)) }
                    }
                }
            },
        )
    }
