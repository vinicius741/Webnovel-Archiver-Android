package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One chat message in an OpenRouter chat-completions request. */
data class OpenRouterMessage(
    val role: String,
    val content: String,
)

/** One model from OpenRouter's public catalog (`GET /api/v1/models`). */
data class OpenRouterModel(
    val id: String,
    val name: String,
    /** USD price per prompt token, as a string (the catalog ships decimal strings). Null when absent. */
    val promptPricePerToken: String?,
    /** USD price per completion token, as a string. Null when absent. */
    val completionPricePerToken: String?,
) {
    val isFree: Boolean
        get() = priceIsZero(promptPricePerToken) && priceIsZero(completionPricePerToken)

    private fun priceIsZero(price: String?): Boolean = price?.toDoubleOrNull() == 0.0
}

/** OpenRouter API failure carrying a user-presentable message (mapped from HTTP status + body). */
class OpenRouterException(
    message: String,
) : Exception(message)

/**
 * Thin OpenRouter REST client shared by all AI features (description generation today; tags and
 * cover art later). Deliberately does NOT ride the app's [com.vinicius741.webnovelarchiver.source.network.NetworkClient]:
 * that stack routes requests through the Cloudflare WebView interceptor and per-source rate
 * limiting meant for novel websites — an authenticated JSON API call to openrouter.ai needs a
 * plain client instead.
 *
 * [baseUrl] is injectable so JVM tests can point the client at MockWebServer.
 *
 * Request and response JSON are built/parsed by hand (JsonObject/JsonParser), never via Gson
 * reflection: R8 renames wire DTO fields in release builds, which silently corrupts the payload
 * (messages lose role/content and the model returns an empty completion).
 */
class OpenRouterClient(
    private val baseUrl: String = PRODUCTION_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val rootUrl = baseUrl.trimEnd('/')

    /**
     * POST /api/v1/chat/completions. Returns the first choice's message content; throws
     * [OpenRouterException] with a friendly message for auth/credit/model/rate-limit failures.
     */
    suspend fun chatCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        maxTokens: Int,
    ): String {
        val body =
            JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", maxTokens)
                add("messages", messages.toJsonArray())
            }
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
        return execute(request) { responseJson, httpCode ->
            when {
                httpCode == 200 -> parseContent(responseJson)
                httpCode == 401 -> throw OpenRouterException("Invalid OpenRouter API key. Check Settings → AI Settings.")
                httpCode == 402 -> throw OpenRouterException("OpenRouter reports insufficient credits for this API key.")
                httpCode == 404 -> throw OpenRouterException("Model not found on OpenRouter: $model")
                httpCode == 429 -> throw OpenRouterException("OpenRouter rate limit reached. Try again in a moment.")
                else -> throw OpenRouterException("OpenRouter request failed (HTTP $httpCode): ${serverMessage(responseJson)}")
            }
        }
    }

    /** GET /api/v1/models — public catalog, no auth required. Used by the model picker. */
    suspend fun fetchModels(): List<OpenRouterModel> {
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/models")
                .get()
                .build()
        return execute(request) { responseJson, httpCode ->
            if (httpCode != 200) throw OpenRouterException("Could not load the OpenRouter model list (HTTP $httpCode).")
            responseJson
                .getAsJsonArray("data")
                ?.mapNotNull { element ->
                    val model = element.asJsonObject
                    val id = model.string("id") ?: return@mapNotNull null
                    val pricing = model.getAsJsonObject("pricing")
                    OpenRouterModel(
                        id = id,
                        name = model.string("name") ?: id,
                        promptPricePerToken = pricing?.string("prompt"),
                        completionPricePerToken = pricing?.string("completion"),
                    )
                }.orEmpty()
                .sortedBy { it.id }
        }
    }

    private suspend fun <T> execute(
        request: Request,
        parse: (JsonObject, Int) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseJson = runCatching { JsonParser.parseString(response.body?.string().orEmpty()) }.getOrNull()
                val root = responseJson?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                parse(root, response.code)
            }
        }

    private fun parseContent(responseJson: JsonObject): String {
        val content =
            responseJson
                .getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.string("content")
                ?.trim()
                .orEmpty()
        if (content.isEmpty()) {
            throw OpenRouterException("The model returned an empty description. Try again or pick a different model.")
        }
        return content
    }

    private fun serverMessage(responseJson: JsonObject): String =
        responseJson
            .getAsJsonObject("error")
            ?.string("message")
            ?.takeIf { it.isNotBlank() }
            ?: "no error detail"

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun List<OpenRouterMessage>.toJsonArray(): JsonArray =
        JsonArray().apply {
            this@toJsonArray.forEach { message ->
                add(
                    JsonObject().apply {
                        addProperty("role", message.role)
                        addProperty("content", message.content)
                    },
                )
            }
        }

    companion object {
        internal const val PRODUCTION_BASE_URL = "https://openrouter.ai"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // Generation on slower models can take tens of seconds; keep the read budget generous.
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
