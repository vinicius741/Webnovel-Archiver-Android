package com.vinicius741.webnovelarchiver.feature.ai

import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobState
import com.vinicius741.webnovelarchiver.data.repository.setChapterRewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The Chapter Polish section of AI Controls (plan §07 "From AI Controls"): global rewrite and
 * verifier models, the per-novel strength, and the downloaded-chapter list with each chapter's
 * polish status. Generating always goes through the billable preflight confirm.
 */

internal fun ScreenHost.addAiChapterPolishCard(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val settings = repository.getAiSettings()
    val cardView =
        container.card {
            addAiModelRow(
                container = this,
                label = "Rewrite model",
                currentModel = { settings.chapterRewriteModel },
            ) { picked ->
                repository.saveAiSettings(repository.getAiSettings().copy(chapterRewriteModel = picked))
            }
            text(
                "Verified in the spike: gpt-5.6-terra/sol (lowest drift), grok-4.6, glm-5.3, " +
                    "deepseek-v4-pro-0813, kimi-k2-0905. Any model works — these are known-good.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(dp(2), dp(Space.XS), dp(2), 0) }
            spacer(Space.SM)
            addAiModelRow(
                container = this,
                label = "Verifier model",
                currentModel = { settings.chapterVerifierModel },
            ) { picked ->
                repository.saveAiSettings(repository.getAiSettings().copy(chapterVerifierModel = picked))
            }
            text(
                "Must differ from the rewriter; an independent check is what makes a draft appliable.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(dp(2), dp(Space.XS), dp(2), 0) }
            spacer(Space.SM)
            addStrengthRow(this, story)
            spacer(Space.SM)
            addPolishChapterList(this, story)
        }
    container.addView(cardView)
}

private fun ScreenHost.addStrengthRow(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val current = RewriteStrength.fromWire(story.chapterRewriteStrength) ?: RewriteStrength.LIGHT
    val strengthRow =
        LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(Space.XS), dp(2), dp(Space.XS))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
            setOnClickListener {
                showStyledOptionsDialog(
                    "Edit strength",
                    listOf(
                        "Light (default) — merge sparingly, keep isolated punchlines" to {
                            saveStrength(story, RewriteStrength.LIGHT)
                        },
                        "Balanced — rebuild rhythm wherever it drones" to {
                            saveStrength(story, RewriteStrength.BALANCED)
                        },
                    ),
                )
            }
        }
    strengthRow.addView(makeText(container.context, "Edit strength", Type.LABEL_LARGE, colors.onSurface))
    strengthRow.addView(
        makeText(
            container.context,
            "${current.label} — applies to this novel's chapters; Light is the default.",
            Type.BODY_SMALL,
            colors.onSurfaceVariant,
        ),
    )
    container.addView(strengthRow)
}

private fun ScreenHost.saveStrength(
    story: Story,
    strength: RewriteStrength,
) {
    scope.launch {
        repository.setChapterRewriteStrength(story.id, strength)
        if (frameIsAiControls(story.id)) showAiControls(story.id)
    }
}

private fun ScreenHost.addPolishChapterList(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val downloaded = story.chapters.filter { it.downloaded }
    if (downloaded.isEmpty()) {
        container.text(
            "Download at least one chapter before polishing.",
            Type.BODY_SMALL,
            colors.onSurfaceVariant,
        )
        return
    }
    container.text("Chapters", Type.LABEL_LARGE, colors.onSurface)
    container
        .text(
            "Tap a chapter to preview or polish it. The source file is never modified.",
            Type.BODY_SMALL,
            colors.onSurfaceVariant,
        ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.XS)) }
    downloaded.take(200).forEach { chapter ->
        addPolishChapterRow(container, story, chapter)
    }
    if (downloaded.size > 200) {
        container.text("…and ${downloaded.size - 200} more chapters", Type.BODY_SMALL, colors.onSurfaceVariant)
    }
}

private fun ScreenHost.addPolishChapterRow(
    container: LinearLayout,
    story: Story,
    chapter: Chapter,
) {
    val colors = ThemeManager.colors
    val status = polishRowStatus(story.id, chapter.id)
    val busy = jobBusyFor(story.id)
    val row =
        LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(Space.SM), 0, dp(Space.SM))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
            setOnClickListener { onPolishChapterTapped(story, chapter, status) }
        }
    row.addView(
        makeText(container.context, chapter.title.ifBlank { chapter.id }, Type.BODY_MEDIUM, colors.onSurface),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(Space.SM)
        },
    )
    when (status) {
        ChapterPolishRowStatus.Generating ->
            row.addView(makeBadge(container.context, "Polishing…", colors.tertiaryContainer, colors.onTertiaryContainer))
        ChapterPolishRowStatus.DraftReady ->
            row.addView(makeBadge(container.context, "Draft ready", colors.tertiaryContainer, colors.onTertiaryContainer))
        ChapterPolishRowStatus.DraftBlocked ->
            row.addView(makeBadge(container.context, "Flagged", colors.errorContainer, colors.onErrorContainer))
        ChapterPolishRowStatus.AppliedActive ->
            row.addView(makeBadge(container.context, "Polished", colors.tertiaryContainer, colors.onTertiaryContainer))
        ChapterPolishRowStatus.AppliedInactive ->
            row.addView(makeBadge(container.context, "Polished (off)", colors.surfaceVariant, colors.onSurfaceVariant))
        null -> Unit
    }
    if (busy != null && busy.chapterId != chapter.id) {
        row.alpha = 0.5f
    }
    container.addView(row)
}

private fun ScreenHost.onPolishChapterTapped(
    story: Story,
    chapter: Chapter,
    status: ChapterPolishRowStatus?,
) {
    when (status) {
        // A draft or applied rewrite exists: open the comparison screen.
        ChapterPolishRowStatus.DraftReady, ChapterPolishRowStatus.DraftBlocked,
        ChapterPolishRowStatus.AppliedActive, ChapterPolishRowStatus.AppliedInactive,
        -> showChapterRewritePreview(story.id, chapter.id)
        // Nothing yet (or mid-generation): run the preflight for a fresh rewrite.
        ChapterPolishRowStatus.Generating -> toast("Already polishing this chapter")
        null -> confirmChapterPolish(story, chapter)
    }
}

/** The rewrite job for this story, if any — shown as a progress slot under the card. */
internal fun ScreenHost.aiChapterRewriteOperationFor(storyId: String): AiChapterRewriteJobState? = jobBusyFor(storyId)
