package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * A generated-but-unapplied cover draft. The applied cover is not changed; the caller decides via
 * [AppRepository.setAiCover]. [prompt] is kept alongside the image so the UI can show the user
 * exactly what was sent to the image model.
 */
data class AiCoverDraft(
    val prompt: String,
    val bytes: ByteArray,
    val mediaType: String?,
)

/**
 * Generates AI cover art drafts in two billable stages: the description model writes an
 * image-generation prompt from the novel's material (title, author, tags, description, opening
 * chapters), then the configured image model renders it. [draft] runs both stages in one shot;
 * [draftPrompt] + [draftImage] expose the stages individually so the user can edit the prompt in
 * between. The returned draft is preview-only; progress is reported as short user-facing
 * messages, mirroring [AiDescriptionEngine].
 */
@Suppress("TooGenericExceptionCaught") // Track any terminal request failure; receipt persistence remains best effort.
class AiCoverArtEngine(
    private val repository: AppRepository,
    private val client: OpenRouterClient,
) {
    /**
     * Process-lifetime cache of image-model id → supported request parameters (with each
     * parameter's allowed values), fetched from the free public catalog. Null until the first
     * successful fetch; a fetch failure leaves it null so the image request falls back to the
     * minimal model + prompt shape.
     */
    @Volatile
    private var imageModelParametersCache: Map<String, Map<String, List<String>?>>? = null

    /**
     * One-shot flow: writes the image prompt and paints it in a single uninterrupted run. The
     * optional callback lets the background coordinator persist the billed prompt before painting.
     */
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

    /**
     * Stage 1 (staged mode): reads the story's context and asks the description model for an
     * image-generation prompt. Returns the cleaned prompt so the user can edit it before the
     * billable image call. Throws with a user-presentable message when the API key is missing, no
     * chapters are downloaded, or OpenRouter/a model fails.
     */
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
        if (AiDescriptionPlanning.selectContextChapters(context.story).isEmpty()) {
            error("Download at least one chapter before generating an AI cover")
        }

        onProgress("Reading chapters...")
        val chapters = AiContextChapters.read(repository, context.story)
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

    /**
     * Stage 2 (staged mode): paints the given prompt — typically reviewed and possibly edited by
     * the user after stage 1 — with the configured image model. The prompt is cleaned again so a
     * hand-edited draft is trimmed and capped exactly like a fresh model reply.
     */
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
        val params = AiCoverPlanning.buildImageRequestParams(imageModelParameters(context.settings.imageModel))
        val image =
            try {
                client.generateImage(
                    context.apiKey,
                    context.settings.imageModel,
                    cleanedPrompt,
                    aspectRatio = params.aspectRatio,
                    resolution = params.resolution,
                    quality = params.quality,
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
     * Asks the description model for the image prompt, retrying exactly once when the reply comes
     * back empty: reasoning-style models occasionally spend the whole token budget before writing
     * any text, and one retry reliably recovers that flake. HTTP failures are not retried — a
     * second call cannot fix auth, credits, or rate limits.
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
    private suspend fun trackedPromptCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        storyId: String,
        operationId: String,
    ): String =
        try {
            client
                .chatCompletion(apiKey, model, messages, AiCoverPlanning.MAX_OUTPUT_TOKENS)
                .also { result ->
                    recordUsage(
                        storyId = storyId,
                        operationId = operationId,
                        feature = FEATURE_COVER_PROMPT,
                        requestedModel = model,
                        receipt = result.receipt,
                        outcome = OUTCOME_COMPLETED,
                    )
                }.content
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

    private suspend fun imageModelParameters(model: String): Map<String, List<String>?>? {
        imageModelParametersCache?.let { return it[model] }
        val catalog = runCatching { client.fetchImageModels() }.getOrNull() ?: return null
        val parameters = catalog.associate { it.id to it.supportedParameters }
        imageModelParametersCache = parameters
        return parameters[model]
    }

    private companion object {
        const val FEATURE_COVER_PROMPT = "cover_prompt"
        const val FEATURE_COVER_IMAGE = "cover_image"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_EMPTY = "empty"
        const val OUTCOME_FAILED = "failed"
    }
}
