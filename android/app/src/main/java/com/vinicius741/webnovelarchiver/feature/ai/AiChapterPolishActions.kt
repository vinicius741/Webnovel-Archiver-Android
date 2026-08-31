package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiChapterPolishPlanning
import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteForegroundService
import com.vinicius741.webnovelarchiver.ai.AiChapterRewritePlanning
import com.vinicius741.webnovelarchiver.ai.AiChapterRewritePrompts
import com.vinicius741.webnovelarchiver.ai.ChapterBlockParsing
import com.vinicius741.webnovelarchiver.ai.RewriteStoryContext
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.chapterRewriteManifest
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/*
 * Shared chapter-polish actions for both entry points (Reader overflow, AI Controls): billable
 * preflight (estimate + confirm), queue start, and the per-render status index.
 */

/** One render's polish state: manifest + chapterId→status tags; running/queued work overrides
 *  draft/applied entries. */
internal data class PolishStatusIndex(
    val manifest: ChapterRewriteManifestModel,
    val tags: Map<String, String>,
)

internal fun ScreenHost.polishStatusIndex(storyId: String): PolishStatusIndex {
    val manifest = repository.chapterRewriteManifest(storyId)
    val coordinator = app.appContainer.aiChapterRewriteJobCoordinator
    val tags = HashMap<String, String>()
    manifest.drafts.forEach { (chapterId, draft) ->
        tags[chapterId] =
            if (draft.status == "ready") AiChapterPolishPlanning.STATUS_DRAFT_READY else AiChapterPolishPlanning.STATUS_DRAFT_BLOCKED
    }
    manifest.applied.forEach { (chapterId, applied) ->
        tags[chapterId] =
            if (applied.active) AiChapterPolishPlanning.STATUS_APPLIED_ACTIVE else AiChapterPolishPlanning.STATUS_APPLIED_INACTIVE
    }
    coordinator.jobs.value.values
        .filter { it.storyId == storyId }
        .forEach { tags[it.chapterId] = AiChapterPolishPlanning.STATUS_GENERATING }
    coordinator.queue.value
        .filter { it.storyId == storyId }
        .forEach { tags[it.chapterId] = AiChapterPolishPlanning.STATUS_QUEUED }
    return PolishStatusIndex(manifest, tags)
}

/** Queues a chapter for polishing on the process coordinator plus its keep-alive foreground service. */
internal fun ScreenHost.startChapterPolishJob(
    story: Story,
    chapter: Chapter,
) {
    // Shared-slot discipline: one billable story operation at a time, or the second runs with no
    // progress representation in the Details slot.
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    val coordinator = app.appContainer.aiChapterRewriteJobCoordinator
    if (!coordinator.enqueue(story.id, chapter.id, chapter.title)) {
        toast("\"${chapter.title}\" is already polishing or queued")
        return
    }
    AiChapterRewriteForegroundService.start(app)
    val queuedCount = coordinator.queuedFor(story.id).size
    if (queuedCount > 1) {
        toast("Queued \"${chapter.title}\" — $queuedCount chapters in the polish queue")
    } else {
        toast("Polishing \"${chapter.title}\" — you'll be notified when the preview is ready")
    }
    if (frameIsAiControls(story.id)) showAiControls(story.id)
}

/** Billable preflight: cost ceiling, then explicit confirmation. Every run sends copyrighted
 *  story text to the provider and spends credits. */
internal fun ScreenHost.confirmChapterPolish(
    story: Story,
    chapter: Chapter,
    onConfirmed: () -> Unit = { startChapterPolishJob(story, chapter) },
) {
    val settings = repository.getAiSettings()
    scope.launch {
        val estimate = estimatePolishCostUsd(story, listOf(chapter))
        app.runOnUiThread {
            val strength = RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT
            val estimateLine =
                estimate?.let { "Estimated cost: up to $$it (rewrite + verify)." }
                    ?: "Cost estimate unavailable — check the connection or the model picker."
            confirm(
                "Polish \"${chapter.title}\" with ${settings.chapterRewriteModel}?\n\n" +
                    "Strength: ${strength.label}. The chapter text, its title, and the story title are sent to " +
                    "OpenRouter and the selected provider. Rewrite model ${settings.chapterRewriteModel} " +
                    "prefers zero-data-retention routing and steps down only if the provider cannot serve it.\n\n" +
                    "$estimateLine\n\n" +
                    "For personal reading only — do not republish another author's polished chapter.",
                confirmLabel = "Polish chapter",
            ) { onConfirmed() }
        }
    }
}

/**
 * Batch preflight: one aggregate cost ceiling, one confirmation. The shared-slot guard is checked
 * once in the confirm callback, then chapters enqueue straight through the coordinator —
 * [startChapterPolishJob] would reject every chapter after the first once the job bridge mirrors
 * the running chapter into [storyOperation].
 */
internal fun ScreenHost.confirmBatchPolish(
    story: Story,
    chapters: List<Chapter>,
) {
    if (chapters.isEmpty()) {
        toast("No unpolished chapters to queue")
        return
    }
    val settings = repository.getAiSettings()
    scope.launch {
        val estimate = estimatePolishCostUsd(story, chapters)
        app.runOnUiThread {
            val strength = RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT
            val estimateLine =
                estimate?.let { "Estimated cost: up to $$it total (rewrite + verify per chapter)." }
                    ?: "Cost estimate unavailable — check the connection or the model picker."
            confirm(
                "Polish ${chapters.size} chapters with ${settings.chapterRewriteModel}?\n\n" +
                    "Strength: ${strength.label}. Each chapter is rewritten and verified; nothing is applied " +
                    "automatically — you compare and apply every draft yourself.\n\n" +
                    "$estimateLine\n\n" +
                    "Chapters run one at a time; the not-yet-started ones can be cancelled at any time. " +
                    "Chapter text, titles, and the story title are sent to OpenRouter and the selected provider.\n\n" +
                    "For personal reading only — do not republish polished chapters.",
                confirmLabel = "Polish ${chapters.size} chapter${if (chapters.size == 1) "" else "s"}",
            ) {
                // Shared-slot discipline, same as the single-chapter path.
                if (storyOperation != null) {
                    toast("Please wait for the current operation to finish")
                    return@confirm
                }
                val coordinator = app.appContainer.aiChapterRewriteJobCoordinator
                val queuedCount = chapters.count { coordinator.enqueue(story.id, it.id, it.title) }
                if (queuedCount == 0) {
                    toast("Nothing queued — the chapters are already polishing or queued")
                    return@confirm
                }
                AiChapterRewriteForegroundService.start(app)
                toast(
                    "Queued $queuedCount chapter${if (queuedCount == 1) "" else "s"} for polishing — " +
                        "cancel any time from AI Controls",
                )
                if (frameIsAiControls(story.id)) showAiControls(story.id)
            }
        }
    }
}

/** Aggregate worst-case cost ceiling (rewrite + verify) for the given chapters, or null when the catalog is unreachable. */
private suspend fun ScreenHost.estimatePolishCostUsd(
    story: Story,
    chapters: List<Chapter>,
): BigDecimal? =
    withContext(Dispatchers.IO) {
        runCatching {
            val settings = repository.getAiSettings()
            val catalog = app.appContainer.openRouter.fetchModels()
            val rewriteModel = catalog.firstOrNull { it.id == settings.chapterRewriteModel }
            val verifierModel = catalog.firstOrNull { it.id == settings.chapterVerifierModel }
            val systemPrompt = systemPromptForStrength(story)
            chapters.sumOf { chapter ->
                val html = repository.readChapter(chapter) ?: chapter.content ?: return@sumOf BigDecimal.ZERO
                val parsed = ChapterBlockParsing.parseChapter(html)
                val userMessage =
                    AiChapterRewritePlanning.buildRewriteUserMessage(
                        RewriteStoryContext(story.title, story.author, chapter.title),
                        parsed,
                        RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT,
                    )
                AiChapterRewritePlanning
                    .estimateCost(systemPrompt, userMessage, rewriteModel, verifierModel)
                    .totalCostMaxUsd
            }
        }.getOrNull()
    }

private fun systemPromptForStrength(story: Story): String =
    AiChapterRewritePrompts.rewritePromptFor(
        if (RewriteStrength.fromWire(story.chapterRewriteStrength) == RewriteStrength.BALANCED) {
            AiChapterRewritePrompts.REWRITE_BALANCED_VERSION
        } else {
            AiChapterRewritePrompts.REWRITE_LIGHT_VERSION
        },
    )

internal sealed interface ChapterPolishRowStatus {
    data object Generating : ChapterPolishRowStatus

    data object Queued : ChapterPolishRowStatus

    data object DraftReady : ChapterPolishRowStatus

    data object DraftBlocked : ChapterPolishRowStatus

    data object AppliedActive : ChapterPolishRowStatus

    data object AppliedInactive : ChapterPolishRowStatus

    companion object {
        fun fromTag(tag: String?): ChapterPolishRowStatus? =
            when (tag) {
                AiChapterPolishPlanning.STATUS_GENERATING -> Generating
                AiChapterPolishPlanning.STATUS_QUEUED -> Queued
                AiChapterPolishPlanning.STATUS_DRAFT_READY -> DraftReady
                AiChapterPolishPlanning.STATUS_DRAFT_BLOCKED -> DraftBlocked
                AiChapterPolishPlanning.STATUS_APPLIED_ACTIVE -> AppliedActive
                AiChapterPolishPlanning.STATUS_APPLIED_INACTIVE -> AppliedInactive
                else -> null
            }
    }
}

internal fun ScreenHost.jobBusyFor(storyId: String): AiChapterRewriteJobState? =
    app.appContainer.aiChapterRewriteJobCoordinator.jobs.value.values
        .firstOrNull { it.storyId == storyId }
