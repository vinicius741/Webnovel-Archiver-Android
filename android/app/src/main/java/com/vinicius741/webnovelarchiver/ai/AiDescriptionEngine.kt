package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import timber.log.Timber

/**
 * Generates AI story description drafts from the first downloaded chapters. The draft is returned to
 * the caller (the AI Controls screen) for preview and is only persisted when the user applies it.
 * Progress is reported as short user-facing messages forwarded to the screen's progress block.
 */
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
        if (story.isArchived == true) throw IllegalStateException("Archived snapshots are read-only")
        if (AiDescriptionPlanning.selectContextChapters(story).isEmpty()) {
            throw IllegalStateException("Download at least one chapter before generating an AI description")
        }

        onProgress("Reading chapters...")
        val chapters = AiContextChapters.read(repository, story)
        if (chapters.isEmpty()) {
            throw IllegalStateException("Downloaded chapter files are missing; re-download the novel's chapters")
        }

        onProgress("Writing synopsis with ${settings.descriptionModel}...")
        val messages = AiDescriptionPlanning.buildMessages(story, chapters)
        val raw = client.chatCompletion(apiKey, settings.descriptionModel, messages, AiDescriptionPlanning.MAX_OUTPUT_TOKENS)
        val description =
            AiDescriptionPlanning.cleanGeneratedDescription(raw)
                ?: throw IllegalStateException("The model returned an empty description. Try again or pick a different model.")
        Timber.i("AI description drafted for %s with %s", storyId, settings.descriptionModel)
        return description
    }
}
