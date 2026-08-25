package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.math.BigDecimal
import java.util.UUID

/** Everything one tracked model call needs; bundles the parameters under detekt budgets. */
internal data class RewriteCallSpec(
    val feature: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val userMessage: String,
    val maxTokens: Int,
    val temperature: Double,
    val responseFormat: JsonObject?,
    val provider: JsonObject?,
    val storyId: String,
    val operationId: String,
)

/** One provider-routed call outcome plus the tier that actually served it. */
internal data class TrackedCallResult(
    val content: String,
    val tier: String,
    val receipt: OpenRouterResponseReceipt,
)

/** Provider routing tiers, strict first: the engine steps down only on routing 404s. */
internal fun rewriteProviderTiers(): List<Pair<String, JsonObject?>> =
    listOf(
        "strict" to AiChapterRewriteSchemas.strictProviderRouting(),
        "relaxed" to AiChapterRewriteSchemas.relaxedProviderRouting(),
        "none" to null,
    )

/** True when the provider block rejected the request: no endpoint satisfies the routing policy. */
internal fun isRewriteRoutingFailure(error: OpenRouterException): Boolean {
    val message = error.message?.lowercase() ?: return false
    return message.contains("no allowed providers") || message.contains("not a valid provider") ||
        message.contains("data policy") || message.contains("no endpoints")
}

/**
 * A tracked call whose reply was cut off (`finish_reason == "length"`). A hard reject for the
 * rewrite itself (truncated prose must never become a draft); the verifier converts it into a
 * retryable parse failure — a cut-off verdict says nothing about the rewrite it judged.
 */
class OpenRouterTruncatedException(
    message: String,
    receipt: OpenRouterResponseReceipt,
) : OpenRouterException(message, receipt)

/**
 * Per-engine usage ledger for one rewrite operation: records every terminal receipt through the
 * source's ledger and keeps a running cost total so previews quote the exact combined cost.
 */
internal class AiChapterRewriteUsageRecorder(
    private val persist: suspend (AiUsageRecord) -> Unit,
) {
    private val spentByOperation = mutableMapOf<String, BigDecimal>()

    fun operationCostUsd(operationId: String): String? = spentByOperation[operationId]?.stripTrailingZeros()?.toPlainString()

    suspend fun record(
        storyId: String,
        operationId: String,
        feature: String,
        requestedModel: String,
        receipt: OpenRouterResponseReceipt,
        outcome: String,
    ) {
        receipt.costUsd?.toBigDecimalOrNull()?.let { cost ->
            spentByOperation[operationId] = (spentByOperation[operationId] ?: BigDecimal.ZERO) + cost
        }
        try {
            persist(
                AiUsageRecord(
                    id = UUID.randomUUID().toString(),
                    operationId = operationId,
                    storyId = storyId,
                    feature = feature,
                    model = receipt.model ?: requestedModel,
                    generationId = receipt.generationId,
                    promptTokens = receipt.promptTokens,
                    completionTokens = receipt.completionTokens,
                    totalTokens = receipt.totalTokens,
                    reasoningTokens = receipt.reasoningTokens,
                    cachedTokens = receipt.cachedTokens,
                    costUsd = receipt.costUsd,
                    outcome = outcome,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            // Receipt persistence is best effort; a ledger write must not fail the rewrite.
            Timber.w(error, "Could not persist chapter rewrite usage receipt")
        }
    }
}

internal fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

/** Everything one rewrite run needs from settings plus the target story and chapter. */
internal class RewriteContext(
    val settings: AiSettings,
    val apiKey: String,
    val story: Story,
    val chapter: Chapter,
    val effectiveVerifierModel: String,
)

/**
 * The verifier must differ from the rewriter (spike rule: self-verification narrates clean
 * verdicts). When the user picks the same model for both, fall back to a verified alternate
 * and record which model actually verified.
 */
internal fun resolveVerifierModel(settings: AiSettings): String =
    when {
        settings.chapterVerifierModel != settings.chapterRewriteModel -> settings.chapterVerifierModel
        settings.chapterRewriteModel != AiSettings.ALTERNATE_CHAPTER_VERIFIER_MODEL -> AiSettings.ALTERNATE_CHAPTER_VERIFIER_MODEL
        else -> AiSettings.DEFAULT_CHAPTER_VERIFIER_MODEL
    }
