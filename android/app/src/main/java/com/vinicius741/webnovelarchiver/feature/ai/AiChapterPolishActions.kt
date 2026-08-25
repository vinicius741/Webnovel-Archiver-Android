package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteForegroundService
import com.vinicius741.webnovelarchiver.ai.AiChapterRewritePlanning
import com.vinicius741.webnovelarchiver.ai.AiChapterRewritePrompts
import com.vinicius741.webnovelarchiver.ai.ChapterBlockParsing
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.chapterRewriteManifest
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Shared Chapter-polish actions used by both entry points (Reader overflow and AI Controls):
 * the billable preflight (estimate + explicit confirm) and the job start, plus small status
 * helpers for list rows.
 */

/** Starts the rewrite job on the process coordinator plus its keep-alive foreground service. */
internal fun ScreenHost.startChapterPolishJob(
    story: Story,
    chapter: Chapter,
) {
    // Same shared-slot discipline as covers and descriptions: one billable story operation at a
    // time, or the second job would run with no progress representation in the Details slot.
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    val accepted =
        app.appContainer.aiChapterRewriteJobCoordinator.start(story.id, chapter.id, chapter.title)
    if (!accepted) {
        toast("Another chapter polish is already running")
        return
    }
    AiChapterRewriteForegroundService.start(app)
    toast("Polishing \"${chapter.title}\" — you'll be notified when the preview is ready")
    if (frameIsAiControls(story.id)) showAiControls(story.id)
}

/**
 * Preflight before a billable rewrite: fetches the catalog for a cost ceiling, parses the chapter
 * for block counts, then asks for explicit confirmation. Every run sends copyrighted story text
 * to the configured provider and spends OpenRouter credits.
 */
internal fun ScreenHost.confirmChapterPolish(
    story: Story,
    chapter: Chapter,
    onConfirmed: () -> Unit = { startChapterPolishJob(story, chapter) },
) {
    val settings = repository.getAiSettings()
    scope.launch {
        val estimate =
            withContext(Dispatchers.IO) {
                runCatching {
                    val html = repository.readChapter(chapter) ?: chapter.content ?: return@runCatching null
                    val parsed = ChapterBlockParsing.parseChapter(html)
                    val user =
                        AiChapterRewritePlanning.buildRewriteUserMessage(
                            com.vinicius741.webnovelarchiver.ai
                                .RewriteStoryContext(story.title, story.author, chapter.title),
                            parsed,
                            RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT,
                        )
                    val catalog = app.appContainer.openRouter.fetchModels()
                    AiChapterRewritePlanning.estimateCost(
                        systemPrompt = systemPromptForStrength(story),
                        userMessage = user,
                        rewriteModel = catalog.firstOrNull { it.id == settings.chapterRewriteModel },
                        verifierModel = catalog.firstOrNull { it.id == settings.chapterVerifierModel },
                    )
                }.getOrNull()
            }
        app.runOnUiThread {
            val estimateLine =
                estimate?.let { "Estimated cost: up to $${it.totalCostMaxUsd.toPlainString()} (rewrite + verify)." }
                    ?: "Cost estimate unavailable — check the connection or the model picker."
            val strength = RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT
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

private fun systemPromptForStrength(story: Story): String =
    AiChapterRewritePrompts.rewritePromptFor(
        if (RewriteStrength.fromWire(story.chapterRewriteStrength) == RewriteStrength.BALANCED) {
            AiChapterRewritePrompts.REWRITE_BALANCED_VERSION
        } else {
            AiChapterRewritePrompts.REWRITE_LIGHT_VERSION
        },
    )

/** One row's polish status for the AI Controls chapter list. */
internal sealed interface ChapterPolishRowStatus {
    data object Generating : ChapterPolishRowStatus

    data object DraftReady : ChapterPolishRowStatus

    data object DraftBlocked : ChapterPolishRowStatus

    data object AppliedActive : ChapterPolishRowStatus

    data object AppliedInactive : ChapterPolishRowStatus
}

internal fun ScreenHost.polishRowStatus(
    storyId: String,
    chapterId: String,
): ChapterPolishRowStatus? {
    if (app.appContainer.aiChapterRewriteJobCoordinator.jobFor(storyId, chapterId) != null ||
        app.appContainer.aiChapterRewriteJobCoordinator.isBusy()
    ) {
        return ChapterPolishRowStatus.Generating
    }
    val manifest = repository.chapterRewriteManifest(storyId)
    val draft: ChapterRewriteDraftRecord? = manifest.drafts[chapterId]
    if (draft != null) {
        return when (draft.status) {
            "ready" -> ChapterPolishRowStatus.DraftReady
            else -> ChapterPolishRowStatus.DraftBlocked
        }
    }
    val applied = manifest.applied[chapterId] ?: return null
    return if (applied.active) ChapterPolishRowStatus.AppliedActive else ChapterPolishRowStatus.AppliedInactive
}

internal fun ScreenHost.jobBusyFor(storyId: String): AiChapterRewriteJobState? =
    app.appContainer.aiChapterRewriteJobCoordinator.jobs.value.values
        .firstOrNull { it.storyId == storyId }
