package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
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
 * chapters), then the configured image model renders it. The returned draft is preview-only;
 * progress is reported as short user-facing messages, mirroring [AiDescriptionEngine].
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
     * Reads the story's context, asks the description model for an image prompt, then asks the
     * image model for the cover. Throws with a user-presentable message when the API key is
     * missing, no chapters are downloaded, or OpenRouter/a model fails.
     */
    suspend fun draft(
        storyId: String,
        onProgress: (String) -> Unit = {},
    ): AiCoverDraft {
        val settings = repository.getAiSettings()
        val apiKey =
            settings.apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Add your OpenRouter API key in Settings → AI Settings first")
        val story =
            repository.story(storyId)
                ?: throw IllegalArgumentException("Story not found")
        if (story.isArchived == true) error("Archived snapshots are read-only")
        if (AiDescriptionPlanning.selectContextChapters(story).isEmpty()) {
            error("Download at least one chapter before generating an AI cover")
        }

        onProgress("Reading chapters...")
        val chapters = AiContextChapters.read(repository, story)
        if (chapters.isEmpty()) {
            error("Downloaded chapter files are missing; re-download the novel's chapters")
        }

        onProgress("Writing image prompt with ${settings.descriptionModel}...")
        val rawPrompt = writeImagePrompt(apiKey, settings.descriptionModel, story, chapters, onProgress)
        val prompt =
            AiCoverPlanning.cleanGeneratedPrompt(rawPrompt)
                ?: error("The model returned an empty image prompt. Try again or pick a different model.")

        onProgress("Painting cover with ${settings.imageModel}...")
        val params = AiCoverPlanning.buildImageRequestParams(imageModelParameters(settings.imageModel))
        val image =
            client.generateImage(
                apiKey,
                settings.imageModel,
                prompt,
                aspectRatio = params.aspectRatio,
                resolution = params.resolution,
                quality = params.quality,
            )
        Timber.i("AI cover drafted for %s with %s", storyId, settings.imageModel)
        return AiCoverDraft(prompt = prompt, bytes = image.bytes, mediaType = image.mediaType)
    }

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
