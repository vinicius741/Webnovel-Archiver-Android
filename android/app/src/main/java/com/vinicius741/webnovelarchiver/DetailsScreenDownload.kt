package com.vinicius741.webnovelarchiver

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText

/** Auto-refresh cadence (ms) for the Details screen while a download is active. Short enough that
 *  the progress bar + spinners feel live, long enough to avoid thrashing the view tree. */
internal const val DETAILS_REFRESH_MS = 1200L

/** Avoid replacing the RecyclerView hierarchy while a touch/fling gesture is still active. */
internal const val DETAILS_SCROLL_RETRY_MS = 250L

/** Tag stamped on the Details root view so the download refresher can detect navigation away. */
internal const val DETAILS_DOWNLOAD_TAG = "details-download"

/**
 * Decides whether the live download banner should be shown for a story's current queue summary.
 * Shared by [showDetails] (initial render) and [refreshDetailsDownload] (in-place refresh) so the
 * banner appears/disappears by the same rule on the periodic refresh tick.
 */
internal fun shouldShowDetailsBanner(summary: DownloadDetailsPlanning.StoryDownloadSummary): Boolean =
    summary.total > 0 &&
        (summary.isActive || summary.isPaused || (summary.isFinished && (summary.failed > 0 || summary.cancelled > 0)))

/**
 * Handles the periodic Details download refresh *without* rebuilding the screen: reads the latest
 * queue + story, then patches the chapter adapter (per-row status flip to spinner/dot/"Available
 * Offline") and swaps the banner slot's contents in place. This replaces the old
 * `showDetails(storyId)` full-screen re-render — tearing down the whole view tree every ~1.2s while
 * downloading caused a visible flicker (blank frame while the new tree inflated, then scroll
 * snapped back and was restored).
 *
 * [bannerSlot] is a direct reference to the slot view captured at [showDetails] render time, NOT a
 * tree lookup. In compact layout the slot lives inside the RecyclerView's header item, which the
 * LayoutManager recycles (detaches from the window) once the user scrolls past it. A tree walk
 * (`findViewByTag`) would miss the recycled/detached slot and trigger a full rebuild on every tick
 * — the exact flicker this fixes. The direct reference stays valid while detached: patching it is
 * safe and the change shows when the header scrolls back into view.
 *
 * Returns true while the story still has live download work (so the loop should keep refreshing);
 * false once the batch is fully terminal, so the loop can stop (the next navigation will rebuild).
 */
internal fun ScreenHost.refreshDetailsDownload(
    storyId: String,
    bannerSlot: ViewGroup?,
): Boolean {
    val story = storage.getStory(storyId) ?: return false
    val jobsForStory = storage.getQueue().filter { it.storyId == storyId }
    val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    val chapterStatuses = DownloadDetailsPlanning.chapterJobStatuses(jobsForStory)

    // Patch the chapter rows in place via the adapter's update(), which reuses view holders
    // (notifyDataSetChanged) instead of inflating a new tree.
    val chapterList = findDetailsChapterList(frame)
    val adapter =
        chapterList?.adapter?.let {
            it as? ChapterListAdapter
                ?: (it as? androidx.recyclerview.widget.ConcatAdapter)?.adapters?.filterIsInstance<ChapterListAdapter>()?.singleOrNull()
        }
    if (adapter != null) {
        val query = adapter.currentQuery()
        val filter = adapter.currentFilter()
        val filtered = filterDetailsChapters(story, query, filter)
        val isEmptyState = filtered.isEmpty()
        val displayed = if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else filtered
        adapter.update(displayed, story, isEmptyState, query, filter, chapterStatuses)
    }

    // Patch the banner slot in place. The slot reference is stable across header recycling; if the
    // batch is no longer eligible for a banner, just empty the slot (its parent — or the recycled
    // header — keeps it). No showDetails() fallback: the next navigation/render rebuilds naturally.
    if (bannerSlot != null) {
        if (shouldShowDetailsBanner(summary)) {
            bannerSlot.removeAllViews()
            bannerSlot.addView(makeDownloadProgressBanner(app, summary) { showQueue() })
        } else {
            bannerSlot.removeAllViews()
        }
    }

    return summary.isActive || summary.isPaused
}

internal fun findDetailsChapterList(root: View): androidx.recyclerview.widget.RecyclerView? {
    if (root is androidx.recyclerview.widget.RecyclerView) return root
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            findDetailsChapterList(root.getChildAt(index))?.let { return it }
        }
    }
    return null
}

internal fun makeStoryOperationProgress(
    context: Context,
    operation: StoryOperationState,
    indeterminate: Boolean,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, context.dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (indeterminate || operation.progress == null) {
            addView(
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                    layoutParams =
                        LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply {
                            bottomMargin = context.dp(Space.SM)
                        }
                },
            )
        } else {
            addView(
                makeProgress(context, operation.progress).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6)).apply {
                            bottomMargin = context.dp(Space.SM)
                        }
                },
            )
        }

        addView(
            makeText(context, operation.message, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }

/**
 * Live download progress banner for the Details info panel — the native counterpart of the RN
 * `StoryActions` progress block. Shows a determinate bar (fraction of jobs completed) plus a status
 * headline ("Downloading: <chapter> (7/20)", "Queued (…)", "Paused (…)", or the finished summary)
 * and a "Go to Downloads" link. The bar is indeterminate only while the batch is queued with zero
 * progress; once anything completes it goes determinate so the user sees real movement.
 */
internal fun makeDownloadProgressBanner(
    context: Context,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    onViewDownloads: () -> Unit,
): LinearLayout {
    val headline = DownloadDetailsPlanning.headline(summary)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, context.dp(Space.XS), 0, context.dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Indeterminate while purely queued (no completions yet); determinate once work finishes so
        // the bar visibly advances as chapters complete.
        if (summary.completed == 0 && summary.isActive) {
            addView(
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                    layoutParams =
                        LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                            bottomMargin = context.dp(Space.XS)
                        }
                },
            )
        } else {
            addView(
                makeProgress(context, summary.progress).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6)).apply {
                            bottomMargin = context.dp(Space.XS)
                        }
                },
            )
        }
        addView(
            makeText(context, headline, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        addView(
            makeButton(context, "Go to Downloads", Btn.TEXT, R.drawable.wna_list) { onViewDownloads() }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(2)
                    }
            },
        )
    }
}
