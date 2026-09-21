package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.data.storage.CoverEvidenceCache
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

/** Streams chapters, reuses content-addressed judgments, and bounds concurrent TypeSafe calls to four. */
internal class CoverEvidenceSelector(
    private val readChapter: suspend (Chapter) -> String?,
    private val saveUsage: suspend (AiUsageRecord) -> Unit,
    private val client: TypeSafeCoverClient,
    private val cache: CoverEvidenceCache,
) {
    constructor(repository: AppRepository, client: TypeSafeCoverClient, cache: CoverEvidenceCache) :
        this(repository::readChapter, { repository.recordAiUsage(it) }, client, cache)

    suspend fun select(
        story: Story,
        apiKey: String,
        progress: (String) -> Unit,
    ): List<AiDescriptionPlanning.ChapterText> =
        withContext(Dispatchers.IO) {
            val operationId = UUID.randomUUID().toString()
            val chapters = story.chapters.withIndex().filter { it.value.downloaded }
            var candidates = emptyList<CoverEvidencePlanning.Candidate>()
            var reused = 0
            var scanned = 0
            var readableChapters = 0
            for ((position, entry) in chapters.withIndex()) {
                coroutineContext.ensureActive()
                val html = readChapter(entry.value) ?: entry.value.content ?: continue
                readableChapters++
                val passages = CoverEvidencePlanning.passages(entry.index + 1, entry.value.title, HtmlCleanup.htmlToFormattedText(html))
                for (batch in passages.chunked(4)) {
                    val results =
                        coroutineScope {
                            batch
                                .map { passage ->
                                    async {
                                        val body = TypeSafeCoverClient.request(CoverEvidencePlanning.state(story, passage))
                                        val key = CoverEvidencePlanning.cacheKey(body)
                                        val saved = cache.read(key)?.let { runCatching { TypeSafeCoverClient.parse(it) }.getOrNull() }
                                        val judgments =
                                            saved
                                                ?: client
                                                    .evaluate(
                                                        apiKey,
                                                        body,
                                                    ) { json, code -> recordUsage(story.id, operationId, json, code) }
                                                    .let { response ->
                                                        cache.write(key, response)
                                                        TypeSafeCoverClient.parse(response)
                                                    }
                                        CoverEvidencePlanning.Candidate(passage, judgments) to (saved != null)
                                    }
                                }.awaitAll()
                        }
                    scanned += results.size
                    reused += results.count { it.second }
                    candidates = CoverEvidencePlanning.shortlist(candidates + results.map { it.first }, story.chapters.size)
                    progress("Selecting cover evidence: chapter ${position + 1}/${chapters.size}, $scanned passages ($reused cached)")
                }
            }
            check(chapters.isEmpty() || readableChapters > 0) {
                "Downloaded chapter files are missing; re-download the novel's chapters"
            }
            cache.trim()
            CoverEvidencePlanning.select(candidates).also {
                check(it.isNotEmpty()) { "No useful cover evidence found. Select context chapters manually in Generation options." }
            }
        }

    @Suppress("TooGenericExceptionCaught") // Usage must not turn a completed scan into another billed request.
    private suspend fun recordUsage(storyId: String, operationId: String, json: JsonObject, code: Int) {
        try {
            val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject

            fun tokens(field: String) = runCatching { usage?.get(field)?.asLong?.takeIf { it >= 0 } }.getOrNull()
            saveUsage(
                AiUsageRecord(
                    id = UUID.randomUUID().toString(),
                    operationId = operationId,
                    storyId = storyId,
                    feature = "cover_selection",
                    model = CoverEvidencePlanning.MODEL,
                    promptTokens = tokens("input_tokens"),
                    completionTokens = tokens("output_tokens"),
                    outcome = if (code in 200..299) "completed" else "failed",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not persist TypeSafe usage")
        }
    }
}
