package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import java.math.BigDecimal
import kotlin.math.ceil

/** Minimal story metadata sent beside the blocks; everything else stays on device. */
data class RewriteStoryContext(
    val storyTitle: String,
    val author: String,
    val chapterTitle: String,
)

/**
 * Pure planning for the chapter-rewrite pipeline: prompt message construction (SOURCE_DATA
 * framing), structured-output schemas, token budgets, and preflight cost estimates. Ported from
 * the Phase-1 spike (`spike.py`); the budgets and framing are spike-proven, do not re-derive.
 */
object AiChapterRewritePlanning {
    /** Hybrid-reasoning models spend hidden reasoning tokens inside max_tokens; without an
     * allowance the JSON itself truncates (finish_reason=length → hard reject). */
    const val REASONING_TOKEN_ALLOWANCE = 6000

    private const val TOKENS_PER_CHAR = 3.8
    private val BOUNDARY_MARKER = Regex("SOURCE_DATA_(START|END)", RegexOption.IGNORE_CASE)

    fun estimateTokens(text: String): Int = ceil(text.length / TOKENS_PER_CHAR).toInt()

    /**
     * The reply mirrors the serialized user payload (every block id + html) plus JSON escaping
     * overhead, and hybrid-reasoning models burn hidden reasoning tokens inside the same limit —
     * budget generously, the ceiling itself is free.
     */
    fun rewriteMaxTokens(
        userMessage: String,
        modelCompletionCap: Long?,
    ): Int {
        var budget = maxOf(4000, (estimateTokens(userMessage) * 2.2).toInt() + 1000 + REASONING_TOKEN_ALLOWANCE)
        if (modelCompletionCap != null && modelCompletionCap > 0) budget = minOf(budget, modelCompletionCap.toInt())
        return budget
    }

    fun buildRewriteUserMessage(
        context: RewriteStoryContext,
        chapter: ParsedChapter,
        strength: RewriteStrength,
    ): String {
        val payload =
            JsonObject().apply {
                add("story", storyData(context))
                add(
                    "rewrite_profile",
                    JsonObject().apply {
                        addProperty("strength", strength.wire)
                    },
                )
                add(
                    "blocks",
                    JsonArray().apply {
                        chapter.blocks.forEach { block ->
                            add(
                                JsonObject().apply {
                                    addProperty("id", block.id)
                                    addProperty("protected", block.protected)
                                    addProperty("html", block.html)
                                },
                            )
                        }
                    },
                )
            }
        return (
            "Rewrite this chapter's addressable blocks under the contract. Protected blocks are " +
                "returned byte-for-byte. Return every block id exactly once, in order.\n\n" +
                frameSourceData(payload)
        )
    }

    fun buildRepairUserMessage(
        originalUserMessage: String,
        issues: List<RewriteIssue>,
    ): String =
        originalUserMessage + (
            "\n\nYour previous reply failed validation:\n" +
                issues.joinToString("\n") { "- ${it.code}: ${it.detail}" } +
                "\nReturn the corrected complete JSON: every input block id exactly once, in order; " +
                "protected blocks byte-for-byte."
        )

    /**
     * Verifier input rebuilt from the *validated* blocks: merges appear as pairs with empty
     * rewritten_html. Never re-parse polished HTML for ids — that mis-pairs once merges shrink the
     * chapter (the spike's worst bug).
     */
    fun buildVerifierUserMessage(
        context: RewriteStoryContext,
        sourceBlocks: List<ChapterBlock>,
        rewrittenBlocks: List<ChapterBlock>,
    ): String {
        val byId = rewrittenBlocks.associateBy { it.id }
        val payload =
            JsonObject().apply {
                add("story", storyData(context))
                add(
                    "blocks",
                    JsonArray().apply {
                        sourceBlocks.forEach { source ->
                            add(
                                JsonObject().apply {
                                    addProperty("id", source.id)
                                    addProperty("protected", source.protected)
                                    addProperty("source_html", source.html)
                                    addProperty("rewritten_html", byId[source.id]?.html ?: "")
                                },
                            )
                        }
                    },
                )
            }
        return (
            "Verify preservation of the rewritten chapter against the source block pairs. " +
                "A rewritten_html of \"\" means the block was merged into the block above; that is not a " +
                "finding by itself — check the carrier block for the absorbed content and flag it only " +
                "if the absorbed content changed meaning.\n\n" +
                frameSourceData(payload)
        )
    }

    /** Preflight cost ceiling for one rewrite + one verify, from catalog decimal-string prices. */
    fun estimateCost(
        systemPrompt: String,
        userMessage: String,
        rewriteModel: OpenRouterModel?,
        verifierModel: OpenRouterModel?,
    ): RewriteCostEstimate {
        val inputTokens = estimateTokens(systemPrompt) + estimateTokens(userMessage)
        val maxOutput = rewriteMaxTokens(userMessage, rewriteModel?.maxCompletionTokens)
        val rewriteCost =
            (
                BigDecimal(inputTokens) * price(rewriteModel?.promptPricePerToken) +
                    BigDecimal(maxOutput) * price(rewriteModel?.completionPricePerToken)
            )
        val verifyCost =
            (
                BigDecimal((inputTokens * 1.5).toLong()) * price(verifierModel?.promptPricePerToken) +
                    BigDecimal(4000) * price(verifierModel?.completionPricePerToken)
            )
        return RewriteCostEstimate(
            inputTokens = inputTokens,
            maxOutputTokens = maxOutput,
            rewriteCostMaxUsd = rewriteCost,
            verifyCostMaxUsd = verifyCost,
            totalCostMaxUsd = rewriteCost + verifyCost,
        )
    }

    private fun storyData(context: RewriteStoryContext): JsonObject =
        JsonObject().apply {
            addProperty("title", context.storyTitle.safeSourceValue())
            if (context.author.isNotBlank()) addProperty("author", context.author.safeSourceValue())
            if (context.chapterTitle.isNotBlank()) addProperty("chapter_title", context.chapterTitle.safeSourceValue())
            addProperty("license_note", "downloaded for personal reading; rewrite is for the reader's private use only")
        }

    private fun frameSourceData(payload: JsonObject): String = "SOURCE_DATA_START\n${payload.toString().safeSourceValue()}\nSOURCE_DATA_END"

    private fun String.safeSourceValue(): String = replace(BOUNDARY_MARKER, "[source boundary marker removed]")

    private fun price(decimal: String?): BigDecimal = decimal?.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

data class RewriteCostEstimate(
    val inputTokens: Int,
    val maxOutputTokens: Int,
    val rewriteCostMaxUsd: BigDecimal,
    val verifyCostMaxUsd: BigDecimal,
    val totalCostMaxUsd: BigDecimal,
)
