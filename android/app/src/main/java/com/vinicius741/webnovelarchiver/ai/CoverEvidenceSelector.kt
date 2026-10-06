package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.data.storage.CoverEvidenceStore
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
 * Validates downloaded chapters with Jev in batches of the target size and stops as soon as
 * enough useful chapters are found, so cost scales with what the novel needs, not its length.
 */
internal class CoverEvidenceSelector(
    private val readChapter: suspend (Chapter) -> String?,
    private val saveUsage: suspend (AiUsageRecord) -> Unit,
    private val client: JevCoverClient,
    private val cache: CoverEvidenceStore,
) {
    constructor(repository: AppRepository, client: JevCoverClient, cache: CoverEvidenceStore) :
        this(repository::readChapter, { repository.recordAiUsage(it) }, client, cache)

    /** One judged chapter; [cached] marks a content-addressed reuse that did not bill. */
    private data class Outcome(
        val scored: CoverEvidencePlanning.ScoredChapter?,
        val cached: Boolean,
        val readable: Boolean,
    )

    suspend fun select(
        story: Story,
        apiKey: String,
        targetChapters: Int,
        progress: (String) -> Unit,
    ): List<AiDescriptionPlanning.ChapterText> =
        withContext(Dispatchers.IO) {
            val operationId = UUID.randomUUID().toString()
            val downloaded = story.chapters.withIndex().filter { it.value.downloaded }
            val target = targetChapters.coerceIn(1, maxOf(1, downloaded.size))
            val useful = mutableListOf<CoverEvidencePlanning.ScoredChapter>()
            var scanned = 0
            var reused = 0
            var readable = false
            for (batch in downloaded.chunked(target)) {
                coroutineContext.ensureActive()
                val outcomes =
                    coroutineScope {
                        batch.chunked(CONCURRENT_CALLS).flatMap { group ->
                            group.map { entry -> async { judge(story, entry, apiKey, operationId) } }.awaitAll()
                        }
                    }
                scanned += batch.size
                reused += outcomes.count { it.cached }
                readable = readable || outcomes.any { it.readable }
                useful += outcomes.mapNotNull { it.scored }.filter { CoverEvidencePlanning.isUseful(it.judgments) }
                progress(
                    "Selecting cover evidence: ${minOf(useful.size, target)}/$target chapters found " +
                        "($scanned scanned${if (reused > 0) ", $reused cached" else ""})",
                )
                if (useful.size >= target) break
            }
            check(readable) {
                "Downloaded chapter files are missing; re-download the novel's chapters"
            }
            check(useful.isNotEmpty()) {
                "No useful cover evidence found. Select context chapters manually in Generation options."
            }
            cache.trim()
            CoverEvidencePlanning
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
        }

    private suspend fun judge(
        story: Story,
        entry: IndexedValue<Chapter>,
        apiKey: String,
        operationId: String,
    ): Outcome {
        val html = readChapter(entry.value) ?: entry.value.content ?: return Outcome(null, cached = false, readable = false)
        val sample =
            CoverEvidencePlanning.sample(entry.index + 1, entry.value.title, HtmlCleanup.htmlToFormattedText(html))
                ?: return Outcome(null, cached = false, readable = true)
        val body = JevCoverClient.request(CoverEvidencePlanning.state(story, sample))
        val key = CoverEvidencePlanning.cacheKey(body)
        val saved = cache.read(key)?.let { runCatching { JevCoverClient.parse(it) }.getOrNull() }
        val judgments =
            saved
                ?: client
                    .evaluate(
                        apiKey,
                        body,
                    ) { json, code -> recordUsage(story.id, operationId, json, code) }
                    .let { response ->
                        cache.write(key, response)
                        JevCoverClient.parse(response)
                    }
        return Outcome(CoverEvidencePlanning.ScoredChapter(sample, judgments), cached = saved != null, readable = true)
    }

    @Suppress("TooGenericExceptionCaught") // Usage must not turn a completed scan into another billed request.
    private suspend fun recordUsage(
        storyId: String,
        operationId: String,
        json: JsonObject,
        code: Int,
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
                    feature = "cover_selection",
                    model = scalar(json, "model") ?: CoverEvidencePlanning.MODEL,
                    generationId = scalar(json, "id"),
                    promptTokens = tokens("input_tokens"),
                    completionTokens = tokens("output_tokens"),
                    costUsd = AiUsagePlanning.normalizeCost(scalar(usage, "cost")),
                    outcome = if (code in 200..299 && runCatching { JevCoverClient.parse(json) }.isSuccess) "completed" else "failed",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not persist Jev usage")
        }
    }

    private companion object {
        const val CONCURRENT_CALLS = 4
    }
}
