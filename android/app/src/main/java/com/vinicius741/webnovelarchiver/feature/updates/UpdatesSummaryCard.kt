package com.vinicius741.webnovelarchiver.feature.updates

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.text

/** Display counts the Updates summary card renders; computed once by [showUpdates]. */
internal data class UpdatesSummaryCounts(
    val followedCount: Int,
    val storyCount: Int,
    val chapterCount: Int,
    val reviewStoryCount: Int,
    val reviewChapterCount: Int,
    val unavailableCount: Int,
)

/**
 * Summary card at the top of the Updates screen: followed-novel totals, pending-review and
 * source-unavailable notes, the live sync progress line, and the Sync button. Returns the
 * progress-refresh closure so the screen can hand it to the sync orchestrator as onProgress.
 */
internal fun ViewGroup.addUpdatesSummaryCard(
    state: UpdateTrackerScreenState,
    counts: UpdatesSummaryCounts,
    canSync: Boolean,
    onSync: (onProgress: () -> Unit) -> Unit,
): () -> Unit {
    lateinit var progressLabel: TextView
    lateinit var progressBar: ProgressBar

    fun refreshProgress() {
        progressLabel.text = state.progressText()
        progressBar.visibility = if (state.syncing) View.VISIBLE else View.GONE
        progressBar.max = state.total.coerceAtLeast(1)
        progressBar.progress = state.completed.coerceAtMost(progressBar.max)
    }

    fun dp(value: Int): Int = context.dp(value)
    addView(
        card {
            text("Following ${counts.followedCount} novel${plural(counts.followedCount)}", Type.TITLE_MEDIUM)
            text(
                "${counts.storyCount} novel${plural(counts.storyCount)} with ${counts.chapterCount} updated " +
                    "chapter${plural(counts.chapterCount)}",
                Type.BODY_MEDIUM,
                ThemeManager.colors.onSurfaceVariant,
            )
            if (counts.reviewStoryCount > 0) {
                text(
                    "${counts.reviewChapterCount} chapter${plural(counts.reviewChapterCount)} across ${counts.reviewStoryCount} " +
                        "novel${plural(counts.reviewStoryCount)} are awaiting download review. Open a novel to choose chapters.",
                    Type.BODY_SMALL,
                    ThemeManager.colors.secondary,
                )
            }
            if (counts.unavailableCount > 0) {
                text(
                    UpdateTrackerPlanning.unavailableSummary(counts.unavailableCount),
                    Type.BODY_SMALL,
                    ThemeManager.colors.onSurfaceVariant,
                )
            }
            progressLabel = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
            progressLabel.setPadding(0, dp(Space.SM), 0, 0)
            addView(progressLabel)
            progressBar =
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progressTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                    progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.colors.outlineVariant)
                    layoutParams =
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                            topMargin = dp(Space.SM)
                        }
                }
            addView(progressBar)
            fullButton(
                label = if (state.syncing) "Syncing..." else "Sync Followed Novels",
                variant = Btn.FILLED,
                icon = R.drawable.wna_refresh,
                enabled = canSync && !state.syncing,
                topMarginDp = Space.LG,
            ) { onSync(::refreshProgress) }
        },
    )
    return ::refreshProgress
}
