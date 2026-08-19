package com.vinicius741.webnovelarchiver.ai

data class OpenRouterMessage(
    val role: String,
    val content: String,
)

data class OpenRouterModel(
    val id: String,
    val name: String,
    /** USD price per prompt token, as a catalog decimal string. */
    val promptPricePerToken: String?,
    /** USD price per completion token, as a catalog decimal string. */
    val completionPricePerToken: String?,
) {
    val isFree: Boolean
        get() = priceIsZero(promptPricePerToken) && priceIsZero(completionPricePerToken)

    private fun priceIsZero(price: String?): Boolean = price?.toDoubleOrNull() == 0.0
}

/** Model and supported request parameters returned by the public image catalog. */
data class OpenRouterImageModel(
    val id: String,
    val name: String,
    val supportedParameters: Map<String, List<String>?> = emptyMap(),
)

/** Provider billing metadata. Money remains decimal text so aggregation never loses precision. */
data class OpenRouterResponseReceipt(
    val generationId: String? = null,
    val model: String? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedTokens: Long? = null,
    val costUsd: String? = null,
)

data class OpenRouterChatCompletionResult(
    val content: String,
    val receipt: OpenRouterResponseReceipt,
)

data class OpenRouterImage(
    val bytes: ByteArray,
    val mediaType: String?,
    val receipt: OpenRouterResponseReceipt = OpenRouterResponseReceipt(),
)

/** Current spend/limit counters returned by `GET /api/v1/key`. */
data class OpenRouterKeyUsage(
    val usage: String?,
    val usageDaily: String?,
    val usageWeekly: String?,
    val usageMonthly: String?,
    val limit: String?,
    val limitRemaining: String?,
    val limitReset: String?,
)

open class OpenRouterException(
    message: String,
    open val receipt: OpenRouterResponseReceipt? = null,
) : Exception(message)

/** Empty text responses are retryable and can still carry a billed receipt. */
class OpenRouterEmptyCompletionException(
    message: String,
    override val receipt: OpenRouterResponseReceipt = OpenRouterResponseReceipt(),
) : OpenRouterException(message, receipt)
