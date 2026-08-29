package com.vinicius741.webnovelarchiver.feature.ai

import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiChapterPolishPlanning
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.setChapterRewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The Chapter Polish section of AI Controls (plan §07 "From AI Controls"). No inline chapter list:
 * a per-novel strength setting, a status summary, and two actions — Browse chapters (searchable,
 * status-filtered picker) and Batch polish (queue the next unpolished chapters with one confirm).
 * Generating always goes through the billable preflight confirm.
 */

internal fun ScreenHost.addAiChapterPolishCard(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            addStrengthRow(this, story)
            spacer(Space.SM)
            addPolishSummaryAndActions(this, story)
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
    strengthRow.addView(makeText(container.context, "Strength", Type.LABEL_LARGE, colors.onSurface))
    strengthRow.addView(
        makeText(container.context, current.label, Type.BODY_SMALL, colors.onSurfaceVariant),
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

/**
 * The status summary plus the two navigation actions. One manifest read (inside
 * [polishStatusIndex]) feeds the summary, the batch candidate count, and the browse dialog.
 */
private fun ScreenHost.addPolishSummaryAndActions(
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
    val index = polishStatusIndex(story.id)
    val statuses = index.tags
    summaryLineOf(index.manifest)?.let { summary ->
        container.text(summary, Type.BODY_MEDIUM, colors.onSurface)
        container.spacer(Space.SM)
    }
    container.fullButton(
        label = "Browse chapters",
        variant = Btn.FILLED,
        icon = R.drawable.wna_menu_book,
        bottomMarginDp = Space.MD,
    ) { showPolishChapterBrowser(story, statuses) }

    val unpolishedCount =
        AiChapterPolishPlanning.nextUnpolished(story.chapters, { id -> statuses[id] }, BATCH_POLISH_MAX).size
    val queued = app.appContainer.aiChapterRewriteJobCoordinator.queuedFor(story.id)
    if (unpolishedCount > 1 || queued.isNotEmpty()) {
        container.row {
            if (unpolishedCount > 1) {
                button("Batch polish…", Btn.THEME_DEFAULT) { onBatchPolishTapped(story, statuses, unpolishedCount) }
            }
            if (queued.isNotEmpty()) {
                button("Cancel pending (${queued.size})", Btn.TEXT) {
                    val removed = app.appContainer.aiChapterRewriteJobCoordinator.cancelQueued(story.id)
                    toast(if (removed > 0) "Cancelled $removed queued chapter${if (removed == 1) "" else "s"}" else "Nothing queued")
                    if (frameIsAiControls(story.id)) showAiControls(story.id)
                }
            }
        }
    }
}

/** "3 polished · 1 draft ready · 1 flagged", or null when there is nothing to report. */
private fun summaryLineOf(manifest: ChapterRewriteManifestModel): String? =
    AiChapterPolishPlanning.summarize(manifest.drafts, manifest.applied).line()

/** Batch-size chooser; the selected set then goes through the shared billable preflight confirm. */
private fun ScreenHost.onBatchPolishTapped(
    story: Story,
    statuses: Map<String, String>,
    unpolishedCount: Int,
) {
    val counts = (listOf(5, 10, 25).filter { it < unpolishedCount } + unpolishedCount).distinct().sorted()
    showStyledOptionsDialog(
        "Batch polish",
        counts.map { count ->
            "Next $count unpolished chapter${if (count == 1) "" else "s"}" to {
                val chapters =
                    AiChapterPolishPlanning.nextUnpolished(story.chapters, { id -> statuses[id] }, count)
                confirmBatchPolish(story, chapters)
            }
        },
    )
}

// Upper bound on one batch-polish selection, keeping the preflight parse-and-estimate bounded.
private const val BATCH_POLISH_MAX = 25

/** The rewrite job for this story, if any — shown as a progress slot under the card. */
internal fun ScreenHost.aiChapterRewriteOperationFor(storyId: String): AiChapterRewriteJobState? = jobBusyFor(storyId)
