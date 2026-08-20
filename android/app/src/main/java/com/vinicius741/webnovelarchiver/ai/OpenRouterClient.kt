package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

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
@Suppress("TooManyFunctions") // Deliberately one thin client for every AI feature's endpoints.
class OpenRouterClient(
    private val baseUrl: String = PRODUCTION_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val rootUrl = baseUrl.trimEnd('/')

    /**
     * POST /api/v1/chat/completions. Returns the first choice's message content and provider
     * receipt; throws
     * [OpenRouterException] with a friendly message for auth/credit/model/rate-limit failures.
     */
    suspend fun chatCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        maxTokens: Int,
    ): OpenRouterChatCompletionResult {
        val body =
            JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", maxTokens)
                add("messages", messages.toJsonArray())
                add(
                    "reasoning",
                    JsonObject().apply {
                        addProperty("effort", "low")
                        addProperty("exclude", true)
                    },
                )
            }
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
        return execute(request) { responseJson, httpCode ->
            val receipt = responseReceipt(responseJson, requestedModel = model)
            when {
                httpCode == 200 -> parseChatCompletion(responseJson, requestedModel = model)
                httpCode == 401 -> throw OpenRouterException("Invalid OpenRouter API key. Check Settings → AI Settings.", receipt)
                httpCode == 402 -> throw OpenRouterException("OpenRouter reports insufficient credits for this API key.", receipt)
                httpCode == 404 -> throw OpenRouterException("Model not found on OpenRouter: $model", receipt)
                httpCode == 429 -> throw OpenRouterException("OpenRouter rate limit reached. Try again in a moment.", receipt)
                else -> throw OpenRouterException("OpenRouter request failed (HTTP $httpCode): ${serverMessage(responseJson)}", receipt)
            }
        }
    }

    /** GET /api/v1/key — current usage and limit counters for [apiKey]. */
    suspend fun fetchCurrentKeyUsage(apiKey: String): OpenRouterKeyUsage {
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/key")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
        return execute(request) { responseJson, httpCode ->
            if (httpCode != 200) {
                when (httpCode) {
                    401 -> throw OpenRouterException("Invalid OpenRouter API key. Check Settings → AI Settings.")
                    else -> throw OpenRouterException(
                        "Could not load OpenRouter key usage (HTTP $httpCode): ${serverMessage(responseJson)}",
                    )
                }
            }
            val data = responseJson.getAsJsonObject("data") ?: responseJson
            OpenRouterKeyUsage(
                usage = data.decimalString("usage"),
                usageDaily = data.decimalString("usage_daily"),
                usageWeekly = data.decimalString("usage_weekly"),
                usageMonthly = data.decimalString("usage_monthly"),
                limit = data.decimalString("limit"),
                limitRemaining = data.decimalString("limit_remaining"),
                limitReset = data.scalarString("limit_reset"),
            )
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

    /**
     * POST /api/v1/images — generates one image from a prompt. The optional parameters
     * ([aspectRatio], [resolution], [quality], [outputFormat]) are included only when non-null;
     * callers pass null for anything the selected model does not support (the image catalog's
     * `supported_parameters` decides) so the API never rejects an unknown parameter. Throws
     * [OpenRouterException] with a friendly message for auth/credit/model/rate-limit failures.
     */
    suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        aspectRatio: String? = null,
        resolution: String? = null,
        quality: String? = null,
        outputFormat: String? = null,
    ): OpenRouterImage {
        val body =
            JsonObject().apply {
                addProperty("model", model)
                addProperty("prompt", prompt)
                aspectRatio?.let { addProperty("aspect_ratio", it) }
                resolution?.let { addProperty("resolution", it) }
                quality?.let { addProperty("quality", it) }
                outputFormat?.let { addProperty("output_format", it) }
            }
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/images")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
        return execute(request) { responseJson, httpCode ->
            if (httpCode != 200) throw imageFailure(responseJson, httpCode, model)
            parseImage(responseJson, model)
        }
    }

    /** GET /api/v1/images/models — public image-model catalog, no auth required. Used by the cover model picker. */
    suspend fun fetchImageModels(): List<OpenRouterImageModel> {
        val request =
            Request
                .Builder()
                .url("$rootUrl/api/v1/images/models")
                .get()
                .build()
        return execute(request) { responseJson, httpCode ->
            if (httpCode != 200) throw OpenRouterException("Could not load the OpenRouter image model list (HTTP $httpCode).")
            val data = responseJson.getAsJsonArray("data")
            // A 200 without a usable list (unexpected body, empty catalog) must fail loudly: the
            // picker caches successes, so a silent empty would stick for the whole process.
            if (data == null || data.size() == 0) {
                throw OpenRouterException("OpenRouter returned an empty image model list. Try again in a moment.")
            }
            data
                .mapNotNull { element ->
                    val model = element.asJsonObject
                    val id = model.string("id") ?: return@mapNotNull null
                    OpenRouterImageModel(
                        id = id,
                        name = model.string("name") ?: id,
                        supportedParameters = model.supportedParameters(),
                    )
                }.sortedBy { it.id }
        }
    }

    /**
     * The request parameters an image model supports, mapped to each parameter's allowed values
     * when the catalog enumerates them. The image catalog ships each parameter as a map entry
     * (`"aspect_ratio": {"type": "enum", "values": [...]}`) — so the parameter names are the map's
     * keys, NOT a string array (treating it as an array was a release bug: Gson's
     * [JsonObject.getAsJsonArray] casts, so every fetch crashed). The values matter as much as the
     * names: many models accept a parameter with a narrower enum than the endpoint's global one
     * (e.g. recraft models offer `3:4` but not `2:3`), and an out-of-enum value is rejected. A
     * plain string array is also accepted for forward compatibility; spec entries without a
     * `values` array mean the parameter is accepted but unconstrained.
     */
    private fun JsonObject.supportedParameters(): Map<String, List<String>?> {
        val member = get("supported_parameters") ?: return emptyMap()
        return when {
            member.isJsonObject ->
                member.asJsonObject.entrySet().associate { (name, spec) ->
                    name to spec.enumeratedValues()
                }
            member.isJsonArray ->
                member
                    .asJsonArray
                    .mapNotNull { parameter -> parameter.takeIf { it.isJsonPrimitive }?.asString }
                    .associateWith { null }
            else -> emptyMap()
        }
    }

    private fun JsonElement.enumeratedValues(): List<String>? =
        takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("values")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
            ?.takeIf { it.isNotEmpty() }

    private suspend fun <T> execute(
        request: Request,
        parse: (JsonObject, Int) -> T,
    ): T = client.executeOpenRouterJson(request, parse)

    private fun parseChatCompletion(
        responseJson: JsonObject,
        requestedModel: String,
    ): OpenRouterChatCompletionResult {
        val receipt = responseReceipt(responseJson, requestedModel)
        val choice = responseJson.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        val content =
            choice
                ?.getAsJsonObject("message")
                ?.string("content")
                ?.trim()
                .orEmpty()
        val finishReason = choice?.string("finish_reason")
        if (content.isEmpty() && finishReason != "length") {
            throw OpenRouterEmptyCompletionException(
                "The model returned an empty description. Try again or pick a different model.",
                receipt = receipt,
            )
        }
        return OpenRouterChatCompletionResult(
            content = content,
            receipt = receipt,
            finishReason = finishReason,
        )
    }

    /** The friendly [OpenRouterException] for a failed image call; the caller throws it. */
    private fun imageFailure(
        responseJson: JsonObject,
        httpCode: Int,
        model: String,
    ): OpenRouterException =
        when (httpCode) {
            401 -> OpenRouterException("Invalid OpenRouter API key. Check Settings → AI Settings.", responseReceipt(responseJson, model))
            402 -> OpenRouterException("OpenRouter reports insufficient credits for this API key.", responseReceipt(responseJson, model))
            404 -> OpenRouterException("Model not found on OpenRouter: $model", responseReceipt(responseJson, model))
            429 -> OpenRouterException("OpenRouter rate limit reached. Try again in a moment.", responseReceipt(responseJson, model))
            else ->
                OpenRouterException(
                    "OpenRouter request failed (HTTP $httpCode): ${serverMessage(responseJson)}",
                    responseReceipt(responseJson, model),
                )
        }

    private fun parseImage(
        responseJson: JsonObject,
        model: String,
    ): OpenRouterImage {
        val receipt = responseReceipt(responseJson, requestedModel = model)
        val image =
            responseJson
                .getAsJsonArray("data")
                ?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        val base64 =
            image
                ?.string("b64_json")
                ?.takeIf { it.isNotBlank() }
                ?: throw OpenRouterException("$model returned no image. Try again or pick a different model.", receipt)
        // One throw covers both an undecodable payload and a zero-byte decode.
        val bytes =
            runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: throw OpenRouterException("$model returned an unreadable image. Try again or pick a different model.", receipt)
        return OpenRouterImage(
            bytes = bytes,
            mediaType = image.string("media_type"),
            receipt = receipt,
        )
    }

    /** Parses receipt fields without routing provider decimals through floating-point arithmetic. */
    private fun responseReceipt(
        responseJson: JsonObject,
        requestedModel: String,
    ): OpenRouterResponseReceipt {
        val usage = responseJson.getAsJsonObject("usage")
        val completionDetails = usage?.getAsJsonObject("completion_tokens_details")
        val promptDetails = usage?.getAsJsonObject("prompt_tokens_details")
        val costDetails = usage?.getAsJsonObject("cost_details")
        return OpenRouterResponseReceipt(
            generationId = responseJson.scalarString("id"),
            model = responseJson.scalarString("model") ?: requestedModel,
            promptTokens = usage?.longValue("prompt_tokens"),
            completionTokens = usage?.longValue("completion_tokens"),
            totalTokens = usage?.longValue("total_tokens"),
            reasoningTokens = usage?.longValue("reasoning_tokens") ?: completionDetails?.longValue("reasoning_tokens"),
            cachedTokens =
                usage?.longValue("cached_tokens")
                    ?: promptDetails?.longValue("cached_tokens")
                    ?: promptDetails?.longValue("cache_read_input_tokens"),
            costUsd =
                usage?.decimalString("cost")
                    ?: costDetails?.decimalString("total_cost")
                    ?: costDetails?.decimalString("upstream_inference_cost"),
        )
    }

    private fun serverMessage(responseJson: JsonObject): String =
        responseJson
            .getAsJsonObject("error")
            ?.string("message")
            ?.takeIf { it.isNotBlank() }
            ?: "no error detail"

    /** Returns a string-valued JSON primitive, including numeric primitives without Double conversion. */
    private fun JsonObject.scalarString(key: String): String? {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asJsonPrimitive ?: return null
        if (!primitive.isString && !primitive.isNumber) return null
        return primitive.asString
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.decimalString(key: String): String? = scalarString(key)

    private fun JsonObject.longValue(key: String): Long? = scalarString(key)?.toLongOrNull()

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
                // Text generation can take tens of seconds; image generation at 2:3 + medium
                // quality can exceed 90s, so keep the read budget generous.
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
