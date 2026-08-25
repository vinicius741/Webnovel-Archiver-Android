package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.ai.ChapterRewriteValidation.VerifierVerdict
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.recordAiUsage
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.math.BigDecimal
import java.util.UUID

/** Everything a preview/apply flow needs from one completed rewrite run. */
data class AiChapterRewriteDraftOutput(
    val storyId: String,
    val chapterId: String,
    val chapterTitle: String,
    val polishedHtml: String,
    val validation: RewriteValidationResult,
    val verdict: VerifierVerdict?,
    val cadenceComparison: CadenceComparison,
    val model: String,
    val verifierModel: String,
    val promptVersion: String,
    val strengthWire: String,
    val operationId: String,
    val providerTier: String,
    val costUsd: String?,
    /** "ready" | "blocked" | "verify_failed" — only "ready" may be applied. */
    val status: String,
    val sourceSha256: String,
)

/** The engine's repository surface, as a seam so JVM tests can drive it against MockWebServer. */
interface AiChapterRewriteEngineSource {
    fun aiSettings(): AiSettings

    fun story(id: String): Story?

    suspend fun chapterHtml(chapter: Chapter): String?

    suspend fun recordUsage(record: AiUsageRecord)
}

/** Production source: the repository owns settings, story state, chapter reads, and the usage ledger. */
class RepositoryAiChapterRewriteSource(
    private val repository: AppRepository,
) : AiChapterRewriteEngineSource {
    override fun aiSettings(): AiSettings = repository.getAiSettings()

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun chapterHtml(chapter: Chapter): String? = repository.readChapter(chapter)

    override suspend fun recordUsage(record: AiUsageRecord) {
        repository.recordAiUsage(record)
    }
}

/**
 * Runs the single-chapter Verified rewrite flow proven in the Phase-1 spike: one rewrite call
 * (structured outputs, privacy-routed with step-down), deterministic validation of the merge
 * contract, one bounded repair, then an independent cross-model preservation verify. Returns a
 * preview-only draft; nothing is applied to the story.
 *
 * `finish_reason == "length"` is a hard reject — a truncated reply is never partially applied.
 * All re-run attempts are single-bounded-repair only.
 */

@Suppress("TooGenericExceptionCaught", "ThrowsCount", "LongMethod") // Terminal outcomes are recorded before propagating.
class AiChapterRewriteEngine(
    private val source: AiChapterRewriteEngineSource,
    private val client: OpenRouterClient,
) {
    constructor(repository: AppRepository, client: OpenRouterClient) : this(
        RepositoryAiChapterRewriteSource(repository),
        client,
    )

    @Volatile
    private var modelCatalogCache: List<OpenRouterModel>? = null

    private val usage = AiChapterRewriteUsageRecorder { source.recordUsage(it) }

    suspend fun draft(
        storyId: String,
        chapterId: String,
        onProgress: (String) -> Unit = {},
    ): AiChapterRewriteDraftOutput {
        val operationId = UUID.randomUUID().toString()
        val context = rewriteContext(storyId, chapterId)
        val story = context.story

        onProgress("Reading chapter...")
        val rawHtml =
            source.chapterHtml(context.chapter) ?: context.chapter.content
                ?: error("Chapter file is missing; re-download the chapter before polishing it")
        val parsed = ChapterBlockParsing.parseChapter(rawHtml)
        if (parsed.addressable.isEmpty()) error("This chapter has no rewritable prose blocks")

        val strength = RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT
        val promptVersion =
            if (strength ==
                RewriteStrength.LIGHT
            ) {
                AiChapterRewritePrompts.REWRITE_LIGHT_VERSION
            } else {
                AiChapterRewritePrompts.REWRITE_BALANCED_VERSION
            }
        val systemPrompt = AiChapterRewritePrompts.rewritePromptFor(promptVersion)
        val storyContext = RewriteStoryContext(story.title, story.author, context.chapter.title)
        val userMessage = AiChapterRewritePlanning.buildRewriteUserMessage(storyContext, parsed, strength)
        val catalog = modelCatalog()
        val rewriteModelInfo = catalog.firstOrNull { it.id == context.settings.chapterRewriteModel }
        val maxTokens = AiChapterRewritePlanning.rewriteMaxTokens(userMessage, rewriteModelInfo?.maxCompletionTokens)

        onProgress("Rewriting with ${context.settings.chapterRewriteModel}...")
        val routed =
            routedCall { tier, provider ->
                trackedCall(
                    RewriteCallSpec(
                        feature = FEATURE_CHAPTER_REWRITE,
                        apiKey = context.apiKey,
                        model = context.settings.chapterRewriteModel,
                        systemPrompt = systemPrompt,
                        userMessage = userMessage,
                        maxTokens = maxTokens,
                        temperature = 0.6,
                        responseFormat = AiChapterRewriteSchemas.rewriteResponseFormat(),
                        provider = provider,
                        storyId = storyId,
                        operationId = operationId,
                    ),
                    providerTier = tier,
                )
            }

        var validation = parseAndValidate(routed.content, parsed, storyId, operationId, context)
        if (!validation.ok) {
            Timber.i("Chapter rewrite validation failed (%s); one bounded repair", validation.issues.map { it.code })
            onProgress("Validation failed — one repair call...")
            val repair =
                routedCall { tier, provider ->
                    trackedCall(
                        RewriteCallSpec(
                            feature = FEATURE_CHAPTER_REPAIR,
                            apiKey = context.apiKey,
                            model = context.settings.chapterRewriteModel,
                            systemPrompt = systemPrompt,
                            userMessage = AiChapterRewritePlanning.buildRepairUserMessage(userMessage, validation.issues),
                            maxTokens = maxTokens,
                            temperature = 0.3,
                            responseFormat = AiChapterRewriteSchemas.rewriteResponseFormat(),
                            provider = provider,
                            storyId = storyId,
                            operationId = operationId,
                        ),
                        providerTier = tier,
                    )
                }
            validation = parseAndValidate(repair.content, parsed, storyId, operationId, context)
        }
        if (!validation.ok) {
            error(
                "The rewrite did not satisfy the block contract: " +
                    validation.issues.joinToString("; ") { "${it.code} (${it.detail})" } +
                    ". Try again or pick a different model.",
            )
        }

        val polishedBlocks = validation.blocks.filter { it.html.isNotEmpty() }
        val polishedHtml = ChapterBlockParsing.assembleChapterHtml(polishedBlocks) + "\n"
        val cadenceComparison =
            ChapterCadenceReport.compare(
                ChapterCadenceReport.cadenceOf(parsed.blocks),
                ChapterCadenceReport.cadenceOf(polishedBlocks),
            )

        onProgress("Verifying with ${context.effectiveVerifierModel}...")
        val verdict = verify(context, storyContext, parsed, validation, operationId)
        val status =
            when {
                verdict?.parseError != null -> STATUS_VERIFY_FAILED
                ChapterRewriteValidation.blockersOf(verdict ?: VerifierVerdict(emptyList())).isNotEmpty() -> STATUS_BLOCKED
                else -> STATUS_READY
            }
        Timber.i(
            "Chapter rewrite drafted for %s ch %s: status=%s merged=%d cost=%s",
            storyId,
            chapterId,
            status,
            validation.mergedCount,
            usage.operationCostUsd(operationId),
        )
        return AiChapterRewriteDraftOutput(
            storyId = storyId,
            chapterId = chapterId,
            chapterTitle = context.chapter.title,
            polishedHtml = polishedHtml,
            validation = validation,
            verdict = verdict,
            cadenceComparison = cadenceComparison,
            model = context.settings.chapterRewriteModel,
            verifierModel = context.effectiveVerifierModel,
            promptVersion = promptVersion,
            strengthWire = strength.wire,
            operationId = operationId,
            providerTier = routed.tier,
            costUsd = usage.operationCostUsd(operationId),
            status = status,
            sourceSha256 = parsed.sourceSha256,
        )
    }

    private suspend fun rewriteContext(
        storyId: String,
        chapterId: String,
    ): RewriteContext {
        val settings = source.aiSettings()
        val apiKey =
            settings.apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Add your OpenRouter API key in Settings → AI Settings first")
        val story =
            source.story(storyId)
                ?: throw IllegalArgumentException("Story not found")
        if (story.isArchived == true) error("Archived snapshots are read-only")
        val chapter =
            story.chapters.firstOrNull { it.id == chapterId }
                ?: throw IllegalArgumentException("Chapter not found")
        if (!chapter.downloaded) error("Download the chapter before polishing it")
        return RewriteContext(settings, apiKey, story, chapter, resolveVerifierModel(settings))
    }

    /**
     * Verifier pass: one bounded retry when the reply is unusable (unparseable, wrong shape, or
     * truncated by the output limit — the retry escalates the budget for exactly that case);
     * unusable after retry = failure, never a pass.
     */
    private suspend fun verify(
        context: RewriteContext,
        storyContext: RewriteStoryContext,
        parsed: ParsedChapter,
        validation: RewriteValidationResult,
        operationId: String,
    ): VerifierVerdict? {
        val verifierUser =
            AiChapterRewritePlanning.buildVerifierUserMessage(storyContext, parsed.blocks, validation.blocks)
        val systemPrompt = AiChapterRewritePrompts.VERIFIER
        for (attempt in 0..1) {
            val verdict =
                try {
                    val result =
                        routedCall { tier, provider ->
                            trackedCall(
                                RewriteCallSpec(
                                    feature = FEATURE_CHAPTER_VERIFY,
                                    apiKey = context.apiKey,
                                    model = context.effectiveVerifierModel,
                                    systemPrompt = systemPrompt,
                                    userMessage = verifierUser,
                                    maxTokens = 4000 + 2000 * attempt,
                                    temperature = if (attempt == 0) 0.1 else 0.0,
                                    responseFormat = AiChapterRewriteSchemas.verifierResponseFormat(),
                                    provider = provider,
                                    storyId = context.story.id,
                                    operationId = operationId,
                                ),
                                providerTier = tier,
                            )
                        }
                    ChapterRewriteValidation.parseVerifierVerdict(result.content)
                } catch (truncated: OpenRouterTruncatedException) {
                    // A cut-off verdict is not evidence about the rewrite; treat it like an
                    // unparseable reply so the escalating retry budget actually gets used.
                    VerifierVerdict(emptyList(), parseError = "verifier reply truncated by the output limit")
                }
            if (verdict.parseError == null || attempt == 1) return verdict
            Timber.i("Verifier reply unusable; one retry with a larger budget")
        }
        return null
    }

    /**
     * Steps provider routing down (strict → relaxed → none) only on routing 404s, recording the
     * tier that actually served the call. Privacy stays strict unless the provider cannot route it.
     */
    private suspend fun routedCall(call: suspend (String, JsonObject?) -> TrackedCallResult): TrackedCallResult {
        var lastRoutingError: OpenRouterException? = null
        for ((tier, provider) in rewriteProviderTiers()) {
            try {
                return call(tier, provider)
            } catch (error: OpenRouterException) {
                if (!isRewriteRoutingFailure(error)) throw error
                lastRoutingError = error
                Timber.i("Provider routing failed at %s tier; stepping down", tier)
            }
        }
        throw lastRoutingError ?: OpenRouterException("No provider routing tier could serve this model")
    }

    private suspend fun trackedCall(
        spec: RewriteCallSpec,
        providerTier: String,
    ): TrackedCallResult {
        val result =
            try {
                client.chatCompletion(
                    spec.apiKey,
                    spec.model,
                    listOf(
                        OpenRouterMessage("system", spec.systemPrompt),
                        OpenRouterMessage("user", spec.userMessage),
                    ),
                    spec.maxTokens,
                    spec.temperature,
                    spec.responseFormat,
                    spec.provider,
                )
            } catch (error: OpenRouterException) {
                // Routing failures are rethrown unrecorded so routedCall can step down and retry;
                // every other failure is terminal and lands in the ledger before propagating.
                if (isRewriteRoutingFailure(error)) throw error
                usage.record(
                    spec.storyId,
                    spec.operationId,
                    spec.feature,
                    spec.model,
                    error.receipt ?: OpenRouterResponseReceipt(),
                    OUTCOME_FAILED,
                )
                throw error
            }
        if (result.finishReason == "length") {
            usage.record(spec.storyId, spec.operationId, spec.feature, spec.model, result.receipt, OUTCOME_TRUNCATED)
            throw OpenRouterTruncatedException(
                "The model hit its response limit (finish_reason=length) — the rewrite was truncated and is discarded. " +
                    "Try again or pick a model with a larger output limit.",
                result.receipt,
            )
        }
        usage.record(spec.storyId, spec.operationId, spec.feature, spec.model, result.receipt, OUTCOME_COMPLETED)
        return TrackedCallResult(result.content, providerTier, result.receipt)
    }

    private suspend fun parseAndValidate(
        content: String,
        parsed: ParsedChapter,
        storyId: String,
        operationId: String,
        context: RewriteContext,
    ): RewriteValidationResult {
        val reply = ChapterRewriteValidation.parseModelReply(content)
        val validation =
            reply?.let { ChapterRewriteValidation.validateRewrite(it, parsed) }
                ?: RewriteValidationResult(false, listOf(RewriteIssue("unparseable", "reply is not a JSON object")))
        if (!validation.ok) {
            usage.record(
                storyId,
                operationId,
                FEATURE_CHAPTER_REWRITE,
                context.settings.chapterRewriteModel,
                OpenRouterResponseReceipt(),
                OUTCOME_INVALID,
            )
        }
        return validation
    }

    private suspend fun modelCatalog(): List<OpenRouterModel> {
        modelCatalogCache?.let { return it }
        val catalog = runCatching { client.fetchModels() }.getOrDefault(emptyList())
        if (catalog.isNotEmpty()) modelCatalogCache = catalog
        return catalog
    }

    private companion object {
        const val FEATURE_CHAPTER_REWRITE = "chapter_rewrite"
        const val FEATURE_CHAPTER_REPAIR = "chapter_repair"
        const val FEATURE_CHAPTER_VERIFY = "chapter_verify"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_TRUNCATED = "truncated"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_INVALID = "invalid_output"
        const val STATUS_READY = "ready"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_VERIFY_FAILED = "verify_failed"
    }
}
