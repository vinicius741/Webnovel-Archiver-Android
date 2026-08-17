package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import timber.log.Timber

/**
 * A generated-but-unapplied cover draft. Nothing is persisted — the caller decides via
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
     * One-shot flow: writes the image prompt and paints it in a single uninterrupted run, for the
     * "generate in one step" mode on AI Controls.
     */
    suspend fun draft(
        storyId: String,
        onProgress: (String) -> Unit = {},
    ): AiCoverDraft = draftImage(storyId, draftPrompt(storyId, onProgress), onProgress)

    /**
     * Stage 1 (staged mode): reads the story's context and asks the description model for an
     * image-generation prompt. Returns the cleaned prompt so the user can edit it before the
     * billable image call. Throws with a user-presentable message when the API key is missing, no
     * chapters are downloaded, or OpenRouter/a model fails.
     */
    suspend fun draftPrompt(
        storyId: String,
        onProgress: (String) -> Unit = {},
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
    ): AiCoverDraft {
        val context = coverContext(storyId)
        val cleanedPrompt =
            AiCoverPlanning.cleanGeneratedPrompt(prompt)
                ?: error("The image prompt is empty. Edit it or generate a new one before painting the cover.")
        onProgress("Painting cover with ${context.settings.imageModel}...")
        val params = AiCoverPlanning.buildImageRequestParams(imageModelParameters(context.settings.imageModel))
        val image =
            client.generateImage(
                context.apiKey,
                context.settings.imageModel,
                cleanedPrompt,
                aspectRatio = params.aspectRatio,
                resolution = params.resolution,
                quality = params.quality,
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
    ): String {
        val messages = AiCoverPlanning.buildPromptMessages(story, chapters)
        return try {
            client.chatCompletion(apiKey, model, messages, AiCoverPlanning.MAX_OUTPUT_TOKENS)
        } catch (error: OpenRouterEmptyCompletionException) {
            Timber.d(error, "Empty image-prompt completion; retrying once")
            onProgress("Empty reply from the model — retrying the image prompt...")
            client.chatCompletion(apiKey, model, messages, AiCoverPlanning.MAX_OUTPUT_TOKENS)
        }
    }

    private suspend fun imageModelParameters(model: String): Map<String, List<String>?>? {
        imageModelParametersCache?.let { return it[model] }
        val catalog = runCatching { client.fetchImageModels() }.getOrNull() ?: return null
        val parameters = catalog.associate { it.id to it.supportedParameters }
        imageModelParametersCache = parameters
        return parameters[model]
    }
}
