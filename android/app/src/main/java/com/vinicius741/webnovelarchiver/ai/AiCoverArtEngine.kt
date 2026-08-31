package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/** Generated but unapplied; the caller applies it via [AppRepository.setAiCover]. [prompt] is kept for the UI. */
data class AiCoverDraft(
    val prompt: String,
    val bytes: ByteArray,
    val mediaType: String?,
)

/**
 * Generates cover drafts in two billable stages: the description model writes an image prompt,
 * then the image model paints it. [draft] runs both in one shot; [draftPrompt] + [draftImage]
 * expose the stages so the user can edit the prompt in between.
 */
@Suppress("TooGenericExceptionCaught") // Track any terminal request failure; receipt persistence remains best effort.
class AiCoverArtEngine(
    private val repository: AppRepository,
    private val client: OpenRouterClient,
) {
    /** Catalog cache for the process lifetime; null until first success, so failures fall back to the minimal request shape. */
    @Volatile
    private var imageModelParametersCache: Map<String, Map<String, List<String>?>>? = null

    /** One-shot flow: write the prompt then paint it; [onPromptReady] persists the billed prompt before painting. */
    suspend fun draft(
        storyId: String,
        onProgress: (String) -> Unit = {},
        onPromptReady: suspend (String) -> Unit = {},
    ): AiCoverDraft {
        val operationId = UUID.randomUUID().toString()
        val prompt = draftPrompt(storyId, onProgress, operationId)
        onPromptReady(prompt)
        return draftImage(storyId, prompt, onProgress, operationId)
    }

    /** Stage 1: ask the description model for an image prompt, cleaned for user editing before the billable image call. */
    suspend fun draftPrompt(
        storyId: String,
        onProgress: (String) -> Unit = {},
    ): String = draftPrompt(storyId, onProgress, UUID.randomUUID().toString())

    private suspend fun draftPrompt(
        storyId: String,
        onProgress: (String) -> Unit,
        operationId: String,
    ): String {
        val context = coverContext(storyId)
        val contextIndices =
            AiDescriptionPlanning.resolveContextChapters(context.story, context.story.aiContextChapterIndices)
        if (contextIndices.isEmpty()) {
            error(
                if (context.story.aiContextChapterIndices != null) {
                    "The selected chapters are no longer downloaded — pick chapters again in AI Controls"
                } else {
                    "Download at least one chapter before generating an AI cover"
                },
            )
        }

        onProgress("Reading chapters...")
        val chapters = AiContextChapters.read(repository, context.story, contextIndices)
        if (chapters.isEmpty()) {
            error("Downloaded chapter files are missing; re-download the novel's chapters")
        }

        onProgress("Writing image prompt with ${context.settings.descriptionModel}...")
        val rawPrompt =
            writeImagePrompt(
                context.apiKey,
                context.settings.descriptionModel,
                context.story,
                chapters,
                onProgress,
                operationId,
            )
        return AiCoverPlanning.cleanGeneratedPrompt(rawPrompt)
            ?: error("The model returned an empty image prompt. Try again or pick a different model.")
    }

    /** Stage 2: paint the (possibly user-edited) prompt; it is cleaned again exactly like a fresh model reply. */
    suspend fun draftImage(
        storyId: String,
        prompt: String,
        onProgress: (String) -> Unit = {},
    ): AiCoverDraft = draftImage(storyId, prompt, onProgress, UUID.randomUUID().toString())

    private suspend fun draftImage(
        storyId: String,
        prompt: String,
        onProgress: (String) -> Unit,
        operationId: String,
    ): AiCoverDraft {
        val context = coverContext(storyId)
        val cleanedPrompt =
            AiCoverPlanning.cleanGeneratedPrompt(prompt)
                ?: error("The image prompt is empty. Edit it or generate a new one before painting the cover.")
        onProgress("Painting cover with ${context.settings.imageModel}...")
        val imageModel = imageModel(context.settings.imageModel)
        if (imageModel != null && !AiCoverPlanning.supportsRasterOutput(imageModel)) {
            error("${context.settings.imageModel} only produces SVG images, which the app cannot display. Pick a raster image model.")
        }
        val params = AiCoverPlanning.buildImageRequestParams(imageModel?.supportedParameters)
        val image =
            try {
                client.generateImage(
                    context.apiKey,
                    context.settings.imageModel,
                    cleanedPrompt,
                    aspectRatio = params.aspectRatio,
                    resolution = params.resolution,
                    quality = params.quality,
                    outputFormat = params.outputFormat,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordUsage(
                    storyId = storyId,
                    operationId = operationId,
                    feature = FEATURE_COVER_IMAGE,
                    requestedModel = context.settings.imageModel,
                    receipt = (error as? OpenRouterException)?.receipt ?: OpenRouterResponseReceipt(),
                    outcome = OUTCOME_FAILED,
                )
                throw error
            }
        if (!AiCoverPlanning.supportsGeneratedMediaType(image.mediaType)) {
            recordUsage(
                storyId = storyId,
                operationId = operationId,
                feature = FEATURE_COVER_IMAGE,
                requestedModel = context.settings.imageModel,
                receipt = image.receipt,
                outcome = OUTCOME_UNSUPPORTED,
            )
            error("${context.settings.imageModel} returned a vector image, which the app cannot display. Pick a raster image model.")
        }
        recordUsage(
            storyId = storyId,
            operationId = operationId,
            feature = FEATURE_COVER_IMAGE,
            requestedModel = context.settings.imageModel,
            receipt = image.receipt,
            outcome = OUTCOME_COMPLETED,
        )
        Timber.i("AI cover drafted for %s with %s", storyId, context.settings.imageModel)
        return AiCoverDraft(prompt = cleanedPrompt, bytes = image.bytes, mediaType = image.mediaType)
    }

    /** Shared validation for both stages: key present, story exists, snapshot not archived. */
    private suspend fun coverContext(storyId: String): CoverContext {
        val settings = repository.getAiSettings()
        val apiKey =
            settings.apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Add your OpenRouter API key in Settings → AI Settings first")
        val story =
            repository.story(storyId)
                ?: throw IllegalArgumentException("Story not found")
        if (story.isArchived == true) error("Archived snapshots are read-only")
        return CoverContext(settings, apiKey, story)
    }

    private class CoverContext(
        val settings: AiSettings,
        val apiKey: String,
        val story: Story,
    )

    /**
     * Retries exactly once on an empty reply: reasoning models sometimes burn the whole token
     * budget before writing text. HTTP failures are not retried — a second call can't fix auth or credits.
     */
    private suspend fun writeImagePrompt(
        apiKey: String,
        model: String,
        story: Story,
        chapters: List<AiDescriptionPlanning.ChapterText>,
        onProgress: (String) -> Unit,
        operationId: String,
    ): String {
        val messages = AiCoverPlanning.buildPromptMessages(story, chapters)
        return try {
            trackedPromptCompletion(apiKey, model, messages, story.id, operationId)
        } catch (error: OpenRouterEmptyCompletionException) {
            Timber.d(error, "Empty image-prompt completion; retrying once")
            onProgress("Empty reply from the model — retrying the image prompt...")
            trackedPromptCompletion(apiKey, model, messages, story.id, operationId)
        }
    }

    /** Runs one prompt attempt and records every terminal outcome before returning or throwing. */
    @Suppress("ThrowsCount") // Distinct terminal outcomes are recorded before propagating the original failure.
    private suspend fun trackedPromptCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        storyId: String,
        operationId: String,
    ): String {
        val result =
            try {
                client.chatCompletion(apiKey, model, messages, AiCoverPlanning.MAX_OUTPUT_TOKENS)
            } catch (error: OpenRouterEmptyCompletionException) {
                recordUsage(storyId, operationId, FEATURE_COVER_PROMPT, model, error.receipt, OUTCOME_EMPTY)
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val receipt = (error as? OpenRouterException)?.receipt ?: OpenRouterResponseReceipt()
                recordUsage(storyId, operationId, FEATURE_COVER_PROMPT, model, receipt, OUTCOME_FAILED)
                throw error
            }
        if (result.finishReason == "length") {
            recordUsage(storyId, operationId, FEATURE_COVER_PROMPT, model, result.receipt, OUTCOME_TRUNCATED)
            throw OpenRouterException(
                "The model reached its response limit before finishing the image prompt. Try again or pick a different model.",
                result.receipt,
            )
        }
        recordUsage(storyId, operationId, FEATURE_COVER_PROMPT, model, result.receipt, OUTCOME_COMPLETED)
        return result.content
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
            Timber.w(error, "Could not persist AI cover usage receipt")
        }
    }

    private suspend fun imageModel(model: String): OpenRouterImageModel? {
        imageModelParametersCache?.get(model)?.let { return OpenRouterImageModel(model, model, it) }
        val catalog = runCatching { client.fetchImageModels() }.getOrNull() ?: return null
        val parameters = catalog.associate { it.id to it.supportedParameters }
        imageModelParametersCache = parameters
        return catalog.firstOrNull { it.id == model }
    }

    private companion object {
        const val FEATURE_COVER_PROMPT = "cover_prompt"
        const val FEATURE_COVER_IMAGE = "cover_image"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_EMPTY = "empty"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_TRUNCATED = "truncated"
        const val OUTCOME_UNSUPPORTED = "unsupported_output"
    }
}
