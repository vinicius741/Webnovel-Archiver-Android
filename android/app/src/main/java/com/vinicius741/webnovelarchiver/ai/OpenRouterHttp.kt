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
): T = executeOpenRouterJson(request, MAX_JSON_BODY_BYTES, parse)

/**
 * Body-bounded variant (R24): the response is capped at [maxBodyBytes] — image generations get a
 * deliberately larger budget than text/catalog responses, and an oversized body fails instead of
 * buffering unbounded.
 */
internal suspend fun <T> OkHttpClient.executeOpenRouterJson(
    request: Request,
    maxBodyBytes: Long,
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
                            val bodyString = response.body.stringBounded(maxBodyBytes)
                            val json = runCatching { JsonParser.parseString(bodyString) }.getOrNull()
                            val root = json?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                            parse(root, response.code)
                        }.onSuccess { continuation.resumeWith(Result.success(it)) }
                            .onFailure { continuation.resumeWith(Result.failure(it)) }
                    }
                }
            },
        )
    }

/** Reads the body with a byte cap; fails instead of buffering past [maxBytes]. */
private fun okhttp3.ResponseBody.stringBounded(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw IOException("Response body exceeded $maxBytes bytes")
    val source = source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw IOException("Response body exceeded $maxBytes bytes")
    return source.buffer.readUtf8()
}

/** Text/catalog budget; image-generation callers pass [MAX_IMAGE_JSON_BODY_BYTES]. */
internal const val MAX_JSON_BODY_BYTES = 20_000_000L

/** Image JSON is ~4/3 the decoded bitmap bytes; sized for large generated covers (R24). */
internal const val MAX_IMAGE_JSON_BODY_BYTES = 48_000_000L
