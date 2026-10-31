package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.data.storage.ChapterEvidenceStore
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Validates downloaded chapters with the chosen decision model in batches of the target size, in the
 * [EvidenceRubric]'s scan order, and stops once enough useful chapters are found and the rubric's
 * minimum scan is covered, so cost scales with what the novel needs, not its length.
 */
internal class ChapterEvidenceSelector(
    private val readChapter: suspend (Chapter) -> String?,
    private val saveUsage: suspend (AiUsageRecord) -> Unit,
    private val client: DecisionClient,
    private val cache: ChapterEvidenceStore,
) {
    constructor(repository: AppRepository, client: DecisionClient, cache: ChapterEvidenceStore) :
        this(repository::readChapter, { repository.recordAiUsage(it) }, client, cache)

    /** One judged chapter; [cached] marks a content-addressed reuse that did not bill. */
    private data class Outcome(
        val scored: ChapterEvidencePlanning.ScoredChapter?,
        val cached: Boolean,
        val readable: Boolean,
    )

    suspend fun select(
        story: Story,
        apiKey: String,
        model: String,
        targetChapters: Int,
        rubric: EvidenceRubric,
        progress: (String) -> Unit,
    ): List<AiDescriptionPlanning.ChapterText> = selectWithPassages(story, apiKey, model, targetChapters, rubric, progress).chapters

    /** [select] plus every passage the rubric flagged across all judged chapters, in reading order. */
    suspend fun selectWithPassages(
        story: Story,
        apiKey: String,
        model: String,
        targetChapters: Int,
        rubric: EvidenceRubric,
        progress: (String) -> Unit,
    ): ChapterEvidencePlanning.Selection =
        withContext(Dispatchers.IO) {
            val operationId = UUID.randomUUID().toString()
            val downloaded =
                story.chapters.withIndex().filter { it.value.downloaded }.let { chapters ->
                    rubric.scanOrder(chapters.size).map(chapters::get)
                }
            val target = targetChapters.coerceIn(1, maxOf(1, downloaded.size))
            val useful = mutableListOf<ChapterEvidencePlanning.ScoredChapter>()
            val passages = mutableListOf<ChapterEvidencePlanning.EvidencePassage>()
            var scanned = 0
            var reused = 0
            var readable = false
            for (batch in downloaded.chunked(target)) {
                coroutineContext.ensureActive()
                val outcomes =
                    coroutineScope {
                        batch.chunked(CONCURRENT_CALLS).flatMap { group ->
                            group.map { entry -> async { judge(story, entry, apiKey, model, rubric, operationId) } }.awaitAll()
                        }
                    }
                scanned += batch.size
                reused += outcomes.count { it.cached }
                readable = readable || outcomes.any { it.readable }
                useful += outcomes.mapNotNull { it.scored }.filter { it.assessment.useful }
                passages +=
                    outcomes.flatMap {
                        it.scored
                            ?.assessment
                            ?.passages
                            .orEmpty()
                    }
                progress(
                    "Selecting ${rubric.progressSubject}: ${minOf(useful.size, target)}/$target chapters found " +
                        "($scanned scanned${if (reused > 0) ", $reused cached" else ""})",
                )
                if (useful.size >= target && scanned >= rubric.minScannedChapters) break
            }
            check(readable) {
                "Downloaded chapter files are missing; re-download the novel's chapters"
            }
            check(useful.isNotEmpty()) { rubric.noEvidenceMessage }
            cache.trim()
            val chapters =
                ChapterEvidencePlanning
                    .choose(useful, target)
                    .mapNotNull { scored ->
                        val chapter = story.chapters[scored.sample.chapter - 1]
                        val html = readChapter(chapter) ?: chapter.content ?: return@mapNotNull null
                        AiDescriptionPlanning.ChapterText(
                            number = scored.sample.chapter,
                            title = scored.sample.title,
                            text = AiDescriptionPlanning.capChapterText(HtmlCleanup.htmlToFormattedText(html)),
                        )
                    }
            ChapterEvidencePlanning.Selection(chapters, passages.sortedWith(compareBy({ it.chapter }, { it.index })))
        }

    private suspend fun judge(
        story: Story,
        entry: IndexedValue<Chapter>,
        apiKey: String,
        model: String,
        rubric: EvidenceRubric,
        operationId: String,
    ): Outcome {
        val html = readChapter(entry.value) ?: entry.value.content ?: return Outcome(null, cached = false, readable = false)
        val sample =
            rubric.sample(entry.index + 1, entry.value.title, HtmlCleanup.htmlToFormattedText(html))
                ?: return Outcome(null, cached = false, readable = true)
        val body = rubric.request(story, sample, model)
        val key = ChapterEvidencePlanning.cacheKey(body)
        val saved = cache.read(key)?.let { runCatching { rubric.assess(sample, it) }.getOrNull() }
        val assessment =
            saved
                ?: client
                    .evaluate(
                        apiKey,
                        body,
                    ) { json, code ->
                        val valid = code in 200..299 && runCatching { rubric.assess(sample, json) }.isSuccess
                        recordUsage(story.id, operationId, model, rubric, json, valid)
                    }.let { response ->
                        // Only well-formed judgments are cached; an invalid one fails this scan and is asked again next time.
                        rubric.assess(sample, response).also { cache.write(key, response) }
                    }
        return Outcome(ChapterEvidencePlanning.ScoredChapter(sample, assessment), cached = saved != null, readable = true)
    }

    @Suppress("TooGenericExceptionCaught") // Usage must not turn a completed scan into another billed request.
    private suspend fun recordUsage(
        storyId: String,
        operationId: String,
        requestedModel: String,
        rubric: EvidenceRubric,
        json: JsonObject,
        valid: Boolean,
    ) {
        try {
            val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject

            fun scalar(
                json: JsonObject?,
                field: String,
            ): String? = json?.get(field)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asString

            fun tokens(field: String) = scalar(usage, field)?.toLongOrNull()?.takeIf { it >= 0 }
            saveUsage(
                AiUsageRecord(
                    id = UUID.randomUUID().toString(),
                    operationId = operationId,
                    storyId = storyId,
                    feature = rubric.usageFeature,
                    model = scalar(json, "model") ?: requestedModel,
                    generationId = scalar(json, "id"),
                    promptTokens = tokens("input_tokens"),
                    completionTokens = tokens("output_tokens"),
                    costUsd = AiUsagePlanning.normalizeCost(scalar(usage, "cost")),
                    outcome = if (valid) "completed" else "failed",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not persist decision-model usage")
        }
    }

    private companion object {
        const val CONCURRENT_CALLS = 4
    }
}
