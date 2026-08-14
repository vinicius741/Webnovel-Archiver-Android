package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import timber.log.Timber

/**
 * Generates AI story descriptions from the first downloaded chapters and persists them through the
 * repository. Progress is reported as short user-facing messages; the Details screen forwards them
 * to its in-flight operation slot.
 */
class AiDescriptionEngine(
    private val repository: AppRepository,
    private val client: OpenRouterClient,
) {
    /**
     * Reads the story's context chapters, asks the configured model for a synopsis, stores it, and
     * returns the persisted text. Throws with a user-presentable message when the API key is
     * missing, no chapters are downloaded, or OpenRouter/the model fails.
     */
    suspend fun generate(
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
        val contextIndices = AiDescriptionPlanning.selectContextChapters(story)
        if (contextIndices.isEmpty()) {
            throw IllegalStateException("Download at least one chapter before generating an AI description")
        }

        onProgress("Reading chapters...")
        val chapters =
            contextIndices.mapNotNull { index ->
                val chapter = story.chapters[index]
                val html = repository.readChapter(chapter) ?: chapter.content ?: return@mapNotNull null
                AiDescriptionPlanning.ChapterText(
                    number = index + 1,
                    title = chapter.title,
                    text = AiDescriptionPlanning.capChapterText(HtmlCleanup.htmlToFormattedText(html)),
                )
            }
        if (chapters.isEmpty()) {
            throw IllegalStateException("Downloaded chapter files are missing; re-download the novel's chapters")
        }

        onProgress("Writing synopsis with ${settings.descriptionModel}...")
        val messages = AiDescriptionPlanning.buildMessages(story, chapters)
        val raw = client.chatCompletion(apiKey, settings.descriptionModel, messages, AiDescriptionPlanning.MAX_OUTPUT_TOKENS)
        val description =
            AiDescriptionPlanning.cleanGeneratedDescription(raw)
                ?: throw IllegalStateException("The model returned an empty description. Try again or pick a different model.")
        repository.setAiDescription(storyId, description)
        Timber.i("AI description generated for %s with %s", storyId, settings.descriptionModel)
        return description
    }
}
