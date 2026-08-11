package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText

/** Avoid replacing the RecyclerView hierarchy while a touch/fling gesture is still active. */
internal const val DETAILS_SCROLL_RETRY_MS = 250L

/**
 * Decides whether the live download banner should be shown for a story's current queue summary.
 * Shared by [showDetails] (initial render) and [refreshDetailsDownload] (in-place refresh) so the
 * banner appears/disappears by the same rule on each repository event.
 */
internal fun shouldShowDetailsBanner(summary: DownloadDetailsPlanning.StoryDownloadSummary): Boolean =
    summary.total > 0 &&
        (summary.isActive || summary.isPaused || (summary.isFinished && (summary.failed > 0 || summary.cancelled > 0)))

/**
 * Handles a Details download event *without* rebuilding the screen: reads the repository's coherent
 * queue + story snapshots, then patches the header summary, chapter adapter (per-row status flip to
 * spinner/dot/"Available Offline"), and banner slot in place. This replaces the old
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
 */
internal fun ScreenHost.refreshDetailsDownload(
    storyId: String,
    bindings: DetailsBindings,
    isBusy: Boolean,
    snapshot: DownloadUiSnapshot? = null,
) {
    val story = snapshot?.library?.firstOrNull { it.id == storyId } ?: repository.story(storyId) ?: return
    val queue = snapshot?.queue ?: repository.queue()
    val jobsForStory = queue.filter { it.storyId == storyId }
    val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    val chapterStatuses = DownloadDetailsPlanning.chapterJobStatuses(jobsForStory)
    val pacingSnapshots = app.appContainer.downloadPacer.snapshots.value.values
    val waitingChapterIds = waitingChapterIds(jobsForStory, pacingSnapshots, System.currentTimeMillis())

    val pacingStatus =
        detailsPacingStatus(
            storyId = storyId,
            storySourceUrl = story.sourceUrl,
            jobsForStory = jobsForStory,
            snapshots = pacingSnapshots,
            nowMillis = System.currentTimeMillis(),
            allJobs = queue,
        )
    bindings.patchDownloadStatus(this, story, summary, chapterStatuses, waitingChapterIds, pacingStatus, isBusy)
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

/**
 * Whether a story-operation progress block should show an indeterminate spinner. Sync never has a
 * fraction; cleanup/EPUB go determinate once [StoryOperationState.progress] is set.
 */
internal fun storyOperationIndeterminate(operation: StoryOperationState): Boolean =
    when (operation.kind) {
        StoryOperationKind.SYNC -> true
        StoryOperationKind.CLEANUP, StoryOperationKind.EPUB -> operation.progress == null
    }

/**
 * Swaps the contents of a Details operation-progress slot in place. Used for subsequent cleanup /
 * EPUB / sync message ticks so the whole Details tree is not rebuilt (that full rebuild caused a
 * visible flicker once per chapter during text cleanup).
 */
internal fun renderStoryOperationProgress(
    slot: ViewGroup,
    operation: StoryOperationState,
) {
    slot.removeAllViews()
    slot.addView(
        makeStoryOperationProgress(
            slot.context,
            operation,
            indeterminate = storyOperationIndeterminate(operation),
        ),
    )
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
    pacingStatus: DownloadPacingUiStatus? = null,
    onViewDownloads: () -> Unit,
): LinearLayout {
    val headline = pacingStatus?.let(DownloadPacingUiPlanning::storyHeadline) ?: DownloadDetailsPlanning.headline(summary)
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
        pacingStatus
            ?.chapterTitle
            ?.takeIf { it.isNotBlank() }
            ?.let { chapterTitle ->
                addView(
                    makeText(context, "“$chapterTitle”", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, context.dp(2), 0, 0)
                    },
                )
            }
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

internal fun detailsPacingStatus(
    storyId: String,
    storySourceUrl: String,
    jobsForStory: List<DownloadJob>,
    snapshots: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
    allJobs: List<DownloadJob> = jobsForStory,
): DownloadPacingUiStatus? =
    DownloadPacingUiPlanning.storyStatus(
        storyId = storyId,
        providerName =
            SourceRegistry.getProvider(jobsForStory.firstOrNull()?.sourceId, storySourceUrl)?.name,
        storyJobs = jobsForStory,
        snapshots = snapshots,
        nowMillis = nowMillis,
        allJobs = allJobs,
    )

/** Chapters held only by the configured request delay, not yet fetching their content. */
internal fun waitingChapterIds(
    jobs: List<DownloadJob>,
    snapshots: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
): Set<String> {
    val waitingJobIds = DownloadPacingUiPlanning.waitingJobs(snapshots, jobs, nowMillis).keys
    return jobs
        .asSequence()
        .filter { it.id in waitingJobIds }
        .map { it.chapter.id }
        .toSet()
}

internal fun ScreenHost.refreshDetailsPacingBanner(
    storyId: String,
    bindings: DetailsBindings,
    snapshots: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
) {
    val story = repository.story(storyId) ?: return
    val queue = repository.queue()
    val jobsForStory = queue.filter { it.storyId == storyId }
    val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    if (!shouldShowDetailsBanner(summary)) return
    val pacingStatus = detailsPacingStatus(storyId, story.sourceUrl, jobsForStory, snapshots, nowMillis, queue)
    bindings.patchDownloadStatus(
        this,
        story,
        summary,
        DownloadDetailsPlanning.chapterJobStatuses(jobsForStory),
        waitingChapterIds(jobsForStory, snapshots, nowMillis),
        pacingStatus,
        storyOperation?.storyId == storyId,
    )
}
