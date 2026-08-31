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

/** Whether the live download banner should show; shared by initial render and in-place refresh. */
internal fun shouldShowDetailsBanner(summary: DownloadDetailsPlanning.StoryDownloadSummary): Boolean =
    summary.total > 0 &&
        (summary.isActive || summary.isPaused || (summary.isFinished && (summary.failed > 0 || summary.cancelled > 0)))

/**
 * Patches the Details download UI (header summary, chapter rows, banner slot) in place instead of
 * a full re-render. The banner slot must be the reference captured at render time — a tree lookup
 * misses it once the RecyclerView header is recycled/detached.
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

/** Sync and AI round-trips have no fraction; cleanup/EPUB go determinate once progress is set. */
internal fun storyOperationIndeterminate(operation: StoryOperationState): Boolean =
    when (operation.kind) {
        StoryOperationKind.SYNC -> true
        StoryOperationKind.CLEANUP, StoryOperationKind.EPUB -> operation.progress == null
        StoryOperationKind.AI_DESCRIPTION, StoryOperationKind.AI_COVER, StoryOperationKind.AI_CHAPTER_REWRITE -> true
    }

/** Swaps the operation-progress slot in place; a full Details rebuild flickers once per chapter. */
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

/** Live download progress banner: progress bar, status headline, and a "Go to Downloads" link. */
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
