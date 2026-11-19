package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.characterRoster
import com.vinicius741.webnovelarchiver.data.repository.listAiCoverVersions
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.data.repository.saveCharacterPortrait
import com.vinicius741.webnovelarchiver.data.repository.saveCharacterRoster
import com.vinicius741.webnovelarchiver.data.storage.ChapterEvidenceStore
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The Characters tab's two billable actions. [findCharacters] counts names on the device for free,
 * then makes one text-model call that turns them into a character list. [draftPortrait] finds the
 * passages that describe one character with the decision model (the lead reuses the cover scan),
 * has the text model write a portrait prompt, and paints it with the image model.
 */
@Suppress("TooGenericExceptionCaught") // Usage receipts are best effort and every terminal failure is recorded first.
class CharacterEngine internal constructor(
    private val repository: AppRepository,
    private val client: OpenRouterClient,
    private val evidenceSelector: ChapterEvidenceSelector,
    private val evidenceStore: ChapterEvidenceStore,
) {
    @Volatile
    private var imageModels: Map<String, OpenRouterImageModel>? = null

    suspend fun findCharacters(
        storyId: String,
        onProgress: (String) -> Unit = {},
    ): List<StoryCharacter> {
        val context = context(storyId)
        val chapters = readChapters(context.story, onProgress)
        onProgress("Finding names...")
        val candidates = withContext(Dispatchers.Default) { CharacterMining.candidates(chapters) }
        if (candidates.isEmpty()) error("No character names were found in the downloaded chapters")
        onProgress("Building the character list with ${context.settings.descriptionModel}...")
        val operationId = UUID.randomUUID().toString()
        val reply =
            complete(
                context,
                CharacterRosterPlanning.buildMessages(context.story, candidates),
                CharacterRosterPlanning.MAX_OUTPUT_TOKENS,
                FEATURE_CHARACTERS,
                operationId,
            )
        val roster =
            withContext(Dispatchers.Default) {
                CharacterRosterPlanning.parse(reply).map { character ->
                    val mentions = CharacterMining.mentions(chapters, character.names)
                    character.copy(
                        mentions = mentions.count,
                        firstChapter = mentions.first,
                        lastChapter = mentions.last,
                        keyChapters = CharacterMining.keyChapters(mentions, CharacterAppearanceRubric.MAX_SCANNED_CHAPTERS),
                    )
                }
            }
        val ordered = CharacterRosterPlanning.ordered(roster)
        // A novel deleted mid-run keeps no files behind.
        repository.story(storyId) ?: error("This novel is no longer in the library")
        repository.saveCharacterRoster(storyId, ordered)
        Timber.i("Found %d characters for %s", ordered.size, storyId)
        return ordered
    }

    /** Paints one portrait and saves it; returns the character it belongs to. */
    suspend fun draftPortrait(
        storyId: String,
        characterId: String,
        onProgress: (String) -> Unit = {},
    ): StoryCharacter {
        val context = context(storyId)
        val character =
            repository.characterRoster(storyId)?.firstOrNull { it.id == characterId }
                ?: error("This character is no longer in the list. Find characters again.")
        val operationId = UUID.randomUUID().toString()
        val evidence = evidence(context, character, onProgress)
        if (evidence.passages.isEmpty()) {
            error(
                "No description of how ${character.name} looks was found in the downloaded chapters, so no portrait " +
                    "was painted. Download more chapters where they appear and try again.",
            )
        }
        onProgress("Writing portrait prompt with ${context.settings.descriptionModel}...")
        val style =
            runCatching { repository.listAiCoverVersions(storyId).let { v -> v.firstOrNull { it.isCurrent } ?: v.firstOrNull() }?.prompt }
                .getOrNull()
        val messages =
            CharacterPortraitPlanning.buildMessages(
                context.story,
                character,
                evidence.passages,
                evidence.earlierPassages,
                evidence.phase,
                styleReference = style,
            )
        val prompt =
            AiCoverPlanning.cleanGeneratedPrompt(
                complete(context, messages, CharacterPortraitPlanning.MAX_OUTPUT_TOKENS, FEATURE_PORTRAIT_PROMPT, operationId),
            ) ?: error("The model returned an empty portrait prompt. Try again or pick a different model.")
        onProgress("Painting ${character.name} with ${context.settings.imageModel}...")
        val image = paint(context, prompt, operationId)
        repository.story(storyId) ?: error("This novel is no longer in the library")
        repository.saveCharacterPortrait(storyId, characterId, prompt, image.bytes, image.mediaType)
        Timber.i("Portrait of %s painted for %s", characterId, storyId)
        return character
    }

    /**
     * The lead uses the cover's automatic scan (cached after the first cover, and scoped to the cover's
     * story phase); everyone else gets a scan of the chapters that name them. The lead falls back to
     * that name scan when the cover scan flagged no appearance passage.
     */
    private suspend fun evidence(
        context: Context,
        character: StoryCharacter,
        onProgress: (String) -> Unit,
    ): ChapterEvidencePlanning.Selection {
        val settings = context.settings
        if (character.role == CharacterRole.PROTAGONIST) {
            onProgress("Reading chapters...")
            val cover =
                runCatching {
                    evidenceSelector.coverSelection(
                        context.story,
                        context.apiKey,
                        settings.decisionModel,
                        settings.coverEvidenceChapters,
                        evidenceStore,
                        onProgress,
                    )
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            if (cover != null && cover.passages.isNotEmpty()) return cover
        }
        val downloaded =
            context.story.chapters.indices
                .filter { context.story.chapters[it].downloaded }
                .map { it + 1 }
        val order = CharacterAppearanceRubric.order(downloaded, character.keyChapters)
        if (order.isEmpty()) return ChapterEvidencePlanning.Selection(emptyList(), emptyList())
        val scan =
            evidenceSelector.scan(
                context.story,
                context.apiKey,
                settings.decisionModel,
                CharacterAppearanceRubric.TARGET_CHAPTERS,
                CharacterAppearanceRubric(character, order),
                onProgress,
            )
        return ChapterEvidencePlanning.Selection(emptyList(), scan.passages)
    }

    private suspend fun readChapters(
        story: Story,
        onProgress: (String) -> Unit,
    ): List<CharacterMining.ChapterText> =
        withContext(Dispatchers.IO) {
            val downloaded = story.chapters.withIndex().filter { it.value.downloaded }
            if (downloaded.isEmpty()) error("Download at least one chapter before finding characters")
            downloaded
                .mapIndexedNotNull { done, (index, chapter) ->
                    coroutineContext.ensureActive()
                    if (done % PROGRESS_EVERY == 0) onProgress("Reading chapters: $done/${downloaded.size}")
                    val html = repository.readChapter(chapter) ?: chapter.content ?: return@mapIndexedNotNull null
                    CharacterMining.ChapterText(index + 1, HtmlCleanup.htmlToFormattedText(html))
                }.also { if (it.isEmpty()) error("Downloaded chapter files are missing; re-download the novel's chapters") }
        }

    /** One text-model call, retried once on an empty reply; a truncated reply fails. */
    @Suppress("ThrowsCount") // Each terminal outcome is recorded before its failure propagates.
    private suspend fun complete(
        context: Context,
        messages: List<OpenRouterMessage>,
        maxTokens: Int,
        feature: String,
        operationId: String,
    ): String {
        val model = context.settings.descriptionModel
        val reasoningEffort = client.reasoningEffortFor(context.settings, model)
        repeat(2) { attempt ->
            val result =
                try {
                    client.chatCompletion(context.apiKey, model, messages, maxTokens, reasoningEffort = reasoningEffort)
                } catch (error: OpenRouterEmptyCompletionException) {
                    recordUsage(context.story.id, operationId, feature, model, error.receipt, OUTCOME_EMPTY)
                    if (attempt == 0) return@repeat
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordUsage(context.story.id, operationId, feature, model, (error as? OpenRouterException)?.receipt, OUTCOME_FAILED)
                    throw error
                }
            if (result.finishReason == "length") {
                recordUsage(context.story.id, operationId, feature, model, result.receipt, OUTCOME_TRUNCATED)
                throw OpenRouterTruncatedException(
                    "The model reached its response limit before finishing. Try again or pick a different model.",
                    result.receipt,
                )
            }
            recordUsage(context.story.id, operationId, feature, model, result.receipt, OUTCOME_COMPLETED)
            return result.content
        }
        error("The model returned an empty reply. Try again or pick a different model.")
    }

    private suspend fun paint(
        context: Context,
        prompt: String,
        operationId: String,
    ): OpenRouterImage {
        val model = context.settings.imageModel
        val catalog = imageModel(model)
        if (catalog != null && !AiCoverPlanning.supportsRasterOutput(catalog)) {
            error("$model only produces SVG images, which the app cannot display. Pick a raster image model.")
        }
        val params = AiCoverPlanning.buildImageRequestParams(catalog?.supportedParameters)
        val image =
            try {
                client.generateImage(
                    context.apiKey,
                    model,
                    prompt,
                    aspectRatio = params.aspectRatio,
                    resolution = params.resolution,
                    quality = params.quality,
                    outputFormat = params.outputFormat,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordUsage(
                    context.story.id,
                    operationId,
                    FEATURE_PORTRAIT_IMAGE,
                    model,
                    (error as? OpenRouterException)?.receipt,
                    OUTCOME_FAILED,
                )
                throw error
            }
        if (!AiCoverPlanning.supportsGeneratedMediaType(image.mediaType)) {
            recordUsage(context.story.id, operationId, FEATURE_PORTRAIT_IMAGE, model, image.receipt, OUTCOME_UNSUPPORTED)
            error("$model returned a vector image, which the app cannot display. Pick a raster image model.")
        }
        recordUsage(context.story.id, operationId, FEATURE_PORTRAIT_IMAGE, model, image.receipt, OUTCOME_COMPLETED)
        return image
    }

    private suspend fun imageModel(model: String): OpenRouterImageModel? {
        imageModels?.let { return it[model] }
        val catalog = runCatching { client.fetchImageModels() }.getOrNull() ?: return null
        return catalog.associateBy { it.id }.also { imageModels = it }[model]
    }

    private suspend fun context(storyId: String): Context {
        val settings = repository.aiSettings.get()
        val apiKey =
            settings.apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Add your OpenRouter API key in Settings → AI Settings first")
        val story = repository.story(storyId) ?: throw IllegalArgumentException("Story not found")
        if (story.isArchived == true) error("Archived snapshots are read-only")
        return Context(settings, apiKey, story)
    }

    private class Context(
        val settings: AiSettings,
        val apiKey: String,
        val story: Story,
    )

    private suspend fun recordUsage(
        storyId: String,
        operationId: String,
        feature: String,
        requestedModel: String,
        receipt: OpenRouterResponseReceipt?,
        outcome: String,
    ) {
        val bill = receipt ?: OpenRouterResponseReceipt()
        try {
            repository.recordAiUsage(
                AiUsageRecord(
                    id = UUID.randomUUID().toString(),
                    operationId = operationId,
                    storyId = storyId,
                    feature = feature,
                    model = bill.model ?: requestedModel,
                    generationId = bill.generationId,
                    promptTokens = bill.promptTokens,
                    completionTokens = bill.completionTokens,
                    totalTokens = bill.totalTokens,
                    reasoningTokens = bill.reasoningTokens,
                    cachedTokens = bill.cachedTokens,
                    costUsd = bill.costUsd,
                    outcome = outcome,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not persist character usage receipt")
        }
    }

    private companion object {
        const val PROGRESS_EVERY = 25
        const val FEATURE_CHARACTERS = "characters"
        const val FEATURE_PORTRAIT_PROMPT = "portrait_prompt"
        const val FEATURE_PORTRAIT_IMAGE = "portrait_image"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_EMPTY = "empty"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_TRUNCATED = "truncated"
        const val OUTCOME_UNSUPPORTED = "unsupported_output"
    }
}
