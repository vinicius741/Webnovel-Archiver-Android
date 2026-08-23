package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * Generates AI story description drafts from the earliest downloaded chapters. The draft is returned to
 * the caller (the AI Controls screen) for preview and is only persisted when the user applies it.
 * Progress is reported as short user-facing messages forwarded to the screen's progress block.
 */
@Suppress("TooGenericExceptionCaught") // Usage must record network/runtime failures and never mask successful generation on write errors.
class AiDescriptionEngine(
    private val repository: AppRepository,
    private val client: OpenRouterClient,
) {
    /**
     * Reads the story's context chapters and asks the configured model for a synopsis. The returned
     * text is a draft: nothing is persisted — the caller decides via [AppRepository.setAiDescription].
     * Throws with a user-presentable message when the API key is missing, no chapters are downloaded,
     * or OpenRouter/the model fails.
     */
    suspend fun draft(
        storyId: String,
        onProgress: (String) -> Unit = {},
    ): String {
        val settings = repository.getAiSettings()
        val apiKey =
            settings.apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Add your OpenRouter API key in Settings → AI Settings first")
        val story =
            repository.story(storyId)
                ?: throw IllegalArgumentException("Story not found")
        if (story.isArchived == true) error("Archived snapshots are read-only")
        val contextIndices = AiDescriptionPlanning.resolveContextChapters(story, story.aiContextChapterIndices)
        if (contextIndices.isEmpty()) {
            error(
                if (story.aiContextChapterIndices != null) {
                    "The selected chapters are no longer downloaded — pick chapters again in AI Controls"
                } else {
                    "Download at least one chapter before generating an AI description"
                },
            )
        }

        onProgress("Reading chapters...")
        val chapters = AiContextChapters.read(repository, story, contextIndices)
        if (chapters.isEmpty()) {
            error("Downloaded chapter files are missing; re-download the novel's chapters")
        }

        onProgress("Writing synopsis with ${settings.descriptionModel}...")
        val messages = AiDescriptionPlanning.buildMessages(story, chapters)
        val operationId = UUID.randomUUID().toString()
        val result =
            try {
                client.chatCompletion(apiKey, settings.descriptionModel, messages, AiDescriptionPlanning.MAX_OUTPUT_TOKENS)
            } catch (error: OpenRouterEmptyCompletionException) {
                recordUsage(
                    storyId = storyId,
                    operationId = operationId,
                    feature = FEATURE_DESCRIPTION,
                    requestedModel = settings.descriptionModel,
                    receipt = error.receipt,
                    outcome = OUTCOME_EMPTY,
                )
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordUsage(
                    storyId = storyId,
                    operationId = operationId,
                    feature = FEATURE_DESCRIPTION,
                    requestedModel = settings.descriptionModel,
                    receipt = (error as? OpenRouterException)?.receipt ?: OpenRouterResponseReceipt(),
                    outcome = OUTCOME_FAILED,
                )
                throw error
            }
        if (result.finishReason == "length") {
            recordUsage(
                storyId = storyId,
                operationId = operationId,
                feature = FEATURE_DESCRIPTION,
                requestedModel = settings.descriptionModel,
                receipt = result.receipt,
                outcome = OUTCOME_TRUNCATED,
            )
            throw OpenRouterException(
                "The model reached its response limit before finishing. Try again or pick a different model.",
                result.receipt,
            )
        }
        val description =
            AiDescriptionPlanning.cleanGeneratedDescription(result.content)
                ?: run {
                    recordUsage(
                        storyId = storyId,
                        operationId = operationId,
                        feature = FEATURE_DESCRIPTION,
                        requestedModel = settings.descriptionModel,
                        receipt = result.receipt,
                        outcome = OUTCOME_INVALID,
                    )
                    error("The model returned an unusable description. Try again or pick a different model.")
                }
        recordUsage(
            storyId = storyId,
            operationId = operationId,
            feature = FEATURE_DESCRIPTION,
            requestedModel = settings.descriptionModel,
            receipt = result.receipt,
            outcome = OUTCOME_COMPLETED,
        )
        Timber.i("AI description drafted for %s with %s", storyId, settings.descriptionModel)
        return description
    }

    private suspend fun recordUsage(
        storyId: String,
        operationId: String,
        feature: String,
        requestedModel: String,
        receipt: OpenRouterResponseReceipt,
        outcome: String,
    ) {
        try {
            repository.recordAiUsage(
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
        } catch (error: Exception) {
            // A full or unavailable disk must not turn an already-billed, successful generation
            // into an apparent model failure. Live key totals remain available in Settings.
            Timber.w(error, "Could not persist AI description usage receipt")
        }
    }

    private companion object {
        const val FEATURE_DESCRIPTION = "description"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_EMPTY = "empty"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_TRUNCATED = "truncated"
        const val OUTCOME_INVALID = "invalid_output"
    }
}
