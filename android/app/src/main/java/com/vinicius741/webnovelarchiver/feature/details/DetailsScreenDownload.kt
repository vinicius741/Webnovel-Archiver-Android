package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.feature.downloads.StorySourceCheck
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
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

/** Sync and AI round-trips have no fraction; cleanup/EPUB go determinate once progress is set. */
internal fun storyOperationIndeterminate(operation: StoryOperationState): Boolean =
    when (operation.kind) {
        StoryOperationKind.SYNC -> true
        StoryOperationKind.CLEANUP, StoryOperationKind.EPUB -> operation.progress == null
        StoryOperationKind.AI_DESCRIPTION, StoryOperationKind.AI_COVER -> true
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
    onMessageView: (android.widget.TextView) -> Unit = {},
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
                onMessageView(this)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }

/**
 * Live download progress banner: progress bar, status headline, and a "Go to Downloads" link. When
 * a site check holds this novel's chapters it leads with that and offers the check directly.
 */
internal fun makeDownloadProgressBanner(
    context: Context,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    pacingStatus: DownloadPacingUiStatus? = null,
    sourceCheck: StorySourceCheck? = null,
    onVerify: (StorySourceCheck) -> Unit = {},
    onViewDownloads: () -> Unit,
): LinearLayout {
    val headline =
        sourceCheck?.let { "Paused · ${it.siteName} needs a quick check" }
            ?: pacingStatus?.let(DownloadPacingUiPlanning::storyHeadline)
            ?: DownloadDetailsPlanning.headline(summary)
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
            makeText(
                context,
                headline,
                Type.BODY_SMALL,
                if (sourceCheck != null) ThemeManager.colors.error else ThemeManager.colors.onSurfaceVariant,
            ).apply {
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
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                sourceCheck?.let { check ->
                    addView(makeButton(context, "Verify now", Btn.TONAL) { onVerify(check) })
                }
                addView(
                    makeButton(context, "Go to Downloads", Btn.TEXT, R.drawable.wna_list) { onViewDownloads() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (sourceCheck != null) marginStart = context.dp(Space.SM)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(if (sourceCheck != null) Space.SM else 2)
            },
        )
    }
}
