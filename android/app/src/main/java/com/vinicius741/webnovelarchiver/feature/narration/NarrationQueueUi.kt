package com.vinicius741.webnovelarchiver.feature.narration

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.details.showStoryChapters
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * One story's card in the generation queue: a header (cover, title, counts; opens the story's audio,
 * ⋮ for the story's queue actions), its first jobs, and "Move to front" when it is not
 * already first.
 */
internal fun LinearLayout.queueStoryCard(
    host: ScreenHost,
    model: ListenScreenModel,
    queueCard: ListenQueueCard,
    runningJobId: String?,
    runningRow: RunningJobRow,
): View {
    val colors = ThemeManager.colors
    val group = queueCard.group
    val isFirst = queueCard.isFirst
    val story = queueCard.story
    val queued = group.jobs.size - group.failed
    val card =
        makeCard(context).apply {
            setPadding(0, context.dp(Space.XS + 2), 0, context.dp(Space.XS))
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(Space.SM + 2)
                }
        }
    val counts =
        buildList {
            if (queued > 0) add("$queued queued")
            if (group.failed > 0) add("${group.failed} failed")
        }.joinToString(" · ")
    val cover =
        story?.let {
            host.coverImage(it, COVER_WIDTH_DP, COVER_HEIGHT_DP, tapToOpen = false).apply {
                isClickable = false
                layoutParams = LinearLayout.LayoutParams(context.dp(COVER_WIDTH_DP), context.dp(COVER_HEIGHT_DP))
            }
        }
    val more =
        context.iconButton(R.drawable.wna_more_vert, "Story queue options", colors.onSurfaceVariant) {
            showStoryQueueMenu(host, model, queueCard)
        }
    card.addView(
        makeListRow(
            context,
            title = story?.title ?: "Story no longer in the library",
            supporting = counts,
            leadIcon = R.drawable.wna_headphones,
            lead = cover,
            trailing = more,
        ) { if (story != null) host.showStoryChapters(group.storyId) else showStoryQueueMenu(host, model, queueCard) },
    )
    group.jobs.take(MAX_ROWS_PER_STORY).forEach { job ->
        card.addView(jobRow(host, model, job, queueCard, isRunning = job.id == runningJobId, runningRow))
    }
    if (group.jobs.size > MAX_ROWS_PER_STORY) {
        card.addView(
            makeText(context, "and ${group.jobs.size - MAX_ROWS_PER_STORY} more", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                setPadding(context.dp(Space.LG), context.dp(Space.XS), context.dp(Space.LG), 0)
            },
        )
    }
    if (!isFirst && queued > 0) {
        card.addView(
            makeButton(context, "Move to front", Btn.TEXT, R.drawable.wna_up) { model.moveStoryToTop(group.storyId) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                marginEnd = context.dp(Space.SM)
            },
        )
    }
    return card
}

private fun LinearLayout.jobRow(
    host: ScreenHost,
    model: ListenScreenModel,
    job: NarrationJob,
    queueCard: ListenQueueCard,
    isRunning: Boolean,
    runningRow: RunningJobRow,
): View {
    val colors = ThemeManager.colors
    val chapterTitle = queueCard.chapterTitles[job.id].orEmpty()
    val failed = job.state == NarrationJobState.FAILED
    val (icon, iconTint, iconFill) =
        when {
            isRunning -> Triple(R.drawable.wna_graphic_eq, colors.secondary, colors.secondaryContainer)
            failed -> Triple(R.drawable.wna_error, colors.error, colors.errorContainer)
            else -> Triple(R.drawable.wna_schedule, colors.onSurfaceVariant, colors.elevation3)
        }
    val status =
        makeText(
            context,
            when {
                isRunning -> "Generating"
                failed -> "Failed · ${job.error.orEmpty()}"
                else -> "Queued · ${job.voice.label}"
            },
            Type.BODY_SMALL,
            when {
                failed -> colors.error
                isRunning -> colors.primary
                else -> colors.onSurfaceVariant
            },
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    val line =
        PlayerProgressLine(context, fillColor = colors.secondary, trackColor = colors.elevation4, rounded = true).apply {
            visibility =
                View.GONE
        }
    val texts =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(context, chapterTitle, Type.BODY_MEDIUM, colors.onSurface).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(status)
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(4)).apply { topMargin = context.dp(6) })
        }
    val trailing =
        if (failed) {
            context.iconButton(
                R.drawable.wna_refresh,
                "Retry",
                colors.onSurfaceVariant,
            ) { model.retry(listOf(job.id)) }
        } else {
            context.iconButton(
                R.drawable.wna_close,
                if (isRunning) "Stop and remove from queue" else "Remove from queue",
                colors.onSurfaceVariant,
            ) {
                // This stops generation mid-chapter, so a stray tap must not do it (the menu asked too).
                if (isRunning) {
                    host.confirm("Stop generating this chapter and remove it from the queue?", confirmLabel = "Stop") {
                        model.remove(setOf(job.id))
                    }
                } else {
                    model.remove(setOf(job.id))
                }
            }
        }
    if (isRunning) {
        runningRow.jobId = job.id
        runningRow.status = status
        runningRow.line = line
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(Space.LG), context.dp(6), context.dp(Space.XS), context.dp(6))
        isClickable = true
        isFocusable = true
        background = selectableRipple(colors.onSurface)
        setOnClickListener { showJobMenu(host, model, job, queueCard, isRunning) }
        addView(
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(icon, iconTint))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                background = roundedBg(iconFill, context.dp(10).toFloat())
            },
            LinearLayout.LayoutParams(context.dp(STATUS_ICON_DP), context.dp(STATUS_ICON_DP)).apply { marginEnd = context.dp(Space.MD) },
        )
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(trailing)
    }
}

private fun showStoryQueueMenu(
    host: ScreenHost,
    model: ListenScreenModel,
    queueCard: ListenQueueCard,
) {
    val group = queueCard.group
    val items = mutableListOf<OptionsDialogItem>()
    if (!queueCard.isFirst) {
        items += OptionsDialogItem.Option("Move to front") { model.moveStoryToTop(group.storyId) }
    }
    if (group.failed > 0) {
        items +=
            OptionsDialogItem.Option("Retry failed (${group.failed})") {
                model.retry(group.jobs.filter { it.state == NarrationJobState.FAILED }.map { it.id })
            }
    }
    if (queueCard.story != null) {
        items += OptionsDialogItem.Option("Open in the story") { host.showStoryChapters(group.storyId) }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option("Remove this story from the queue (${group.jobs.size})", destructive = true) {
            model.remove(group.jobs.map { it.id }.toSet())
        }
    host.showStyledOptionsDialog(queueCard.story?.title ?: "Story", items)
}

private fun showJobMenu(
    host: ScreenHost,
    model: ListenScreenModel,
    job: NarrationJob,
    queueCard: ListenQueueCard,
    isRunning: Boolean,
) {
    val items = mutableListOf<OptionsDialogItem>()
    if (!isRunning) {
        items += OptionsDialogItem.Option("Move to front") { model.moveToTop(job.id) }
    }
    if (job.state == NarrationJobState.FAILED) {
        items += OptionsDialogItem.Option("Retry") { model.retry(listOf(job.id)) }
    }
    if (queueCard.story != null) {
        items += OptionsDialogItem.Option("Open in the story") { host.showStoryChapters(job.storyId, job.chapterId) }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option(if (isRunning) "Stop and remove from queue" else "Remove from queue", destructive = true) {
            model.remove(setOf(job.id))
        }
    host.showStyledOptionsDialog(queueCard.chapterTitles[job.id].orEmpty(), items)
}

private const val MAX_ROWS_PER_STORY = 5
private const val COVER_WIDTH_DP = 36
private const val COVER_HEIGHT_DP = 54
private const val STATUS_ICON_DP = 32
