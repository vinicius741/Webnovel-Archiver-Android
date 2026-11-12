package com.vinicius741.webnovelarchiver.feature.updates

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.story.UpdatesCopy
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Entry
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.IconButtonStyle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeSmallSpinner
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One Updates row: small cover, title, a plain-language status line, and the row's action (sync,
 * or download what is missing). Tapping the row opens the novel.
 */
internal fun ScreenHost.makeUpdatesRow(
    entry: Entry,
    section: Section,
    now: () -> Long,
    jobsFor: (String) -> List<DownloadJob>,
    rowBinders: MutableMap<String, UpdatesRowBinder>,
): View {
    val colors = ThemeManager.colors
    val status =
        makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
    val trailing = FrameLayout(app)
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_MIN_HEIGHT_DP)
            setPadding(dp(Spacing.MD), dp(Spacing.SM + 2), dp(Spacing.XS + 2), dp(Spacing.SM + 2))
            background = selectableRipple(colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { showDetails(entry.story.id) }
        }
    row.addView(
        coverImage(entry.story, widthDp = ROW_COVER_WIDTH_DP, heightDp = ROW_COVER_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(Spacing.MD)
        },
    )
    row.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, entry.story.title, Type.TITLE_SMALL, colors.onSurface).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(status)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    row.addView(
        trailing,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart =
                dp(Spacing.SM)
        },
    )

    val binder =
        UpdatesRowBinder(entry) { latest ->
            bindUpdatesRow(latest, section, status, trailing, now(), jobsFor(latest.story.id))
        }
    binder.bind(entry)
    rowBinders[rowKey(section, entry.story.id)] = binder
    return row
}

internal fun rowKey(
    section: Section,
    storyId: String,
): String = "${section.name}:$storyId"

private fun ScreenHost.bindUpdatesRow(
    entry: Entry,
    section: Section,
    status: TextView,
    trailing: FrameLayout,
    now: Long,
    jobs: List<DownloadJob>,
) {
    val colors = ThemeManager.colors
    val story = entry.story
    val syncStatus = updatesState.syncing[story.id]
    val download = DownloadDetailsPlanning.summarizeStoryDownload(jobs).takeIf { it.isActive }
    trailing.removeAllViews()
    when {
        syncStatus != null -> {
            status.text = syncingLine(syncStatus)
            status.setTextColor(colors.primary)
            trailing.addView(spinnerBox(app))
        }
        section == Section.TO_DOWNLOAD && download != null -> {
            status.text = "Downloading · ${download.completed} of ${download.total}"
            status.setTextColor(colors.primary)
            trailing.addView(spinnerBox(app))
        }
        section == Section.TO_DOWNLOAD -> {
            status.text = UpdatesCopy.statusLine(entry, section, now)
            status.setTextColor(colors.onSurfaceVariant)
            trailing.addView(compactDownloadButton(app, entry.missingChapters) { downloadMissingFromUpdates(story) })
        }
        else -> {
            status.text = UpdatesCopy.statusLine(entry, section, now)
            status.setTextColor(
                when (section) {
                    Section.MAY_BE_OUT -> colors.primary
                    Section.RUNNING_LATE -> colors.secondary
                    else -> colors.onSurfaceVariant
                },
            )
            trailing.addView(
                app.iconButton(R.drawable.wna_sync, "Sync ${story.title}", colors.onSurface, IconButtonStyle.Standard) {
                    syncFromUpdates(story)
                },
            )
        }
    }
}

/** Status line of a row whose novel is syncing, from the engine's latest progress message. */
internal fun syncingLine(message: String): String = "Syncing · ${message.trimEnd('.', '…')}"

private fun spinnerBox(context: Context): View =
    FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(ACTION_BOX_DP), context.dp(ACTION_BOX_DP))
        addView(
            makeSmallSpinner(context, SPINNER_DP).apply {
                layoutParams =
                    FrameLayout.LayoutParams(context.dp(SPINNER_DP), context.dp(SPINNER_DP), Gravity.CENTER)
            },
        )
    }

private fun compactDownloadButton(
    context: Context,
    count: Int,
    onClick: () -> Unit,
): Button =
    makeButton(context, count.toString(), Btn.TONAL, R.drawable.wna_download, onClick).apply {
        minHeight = context.dp(COMPACT_BUTTON_DP)
        minimumHeight = context.dp(COMPACT_BUTTON_DP)
        setPadding(context.dp(Spacing.MD), 0, context.dp(Spacing.MD + 2), 0)
        contentDescription = "Download $count chapters"
    }

/** The 7-day release calendar: a bar per day, today first. */
internal fun makeReleaseCalendar(
    context: Context,
    days: List<UpdatesPlanning.CalendarDay>,
): View {
    val colors = ThemeManager.colors
    val max = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val weekday = SimpleDateFormat("EEE", Locale.getDefault())
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        days.forEach { day ->
            addView(calendarColumn(context, day, max, weekday), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}

/** One day of the release calendar: a bar scaled to the busiest day, the count, and the weekday. */
private fun calendarColumn(
    context: Context,
    day: UpdatesPlanning.CalendarDay,
    max: Int,
    weekday: SimpleDateFormat,
): View {
    val colors = ThemeManager.colors
    val fillFraction = (day.count.toFloat() / max).coerceAtLeast(MIN_BAR_FRACTION)
    val track =
        FrameLayout(context).apply {
            background = roundedBg(ColorUtils.setAlphaComponent(colors.onSurface, TRACK_ALPHA), context.dp(Spacing.SM).toFloat())
            clipToOutline = true
            addView(
                View(context).apply {
                    val fill = if (day.isToday) colors.primary else ColorUtils.setAlphaComponent(colors.primary, FILL_ALPHA)
                    background = roundedBg(fill, context.dp(Spacing.XS + 2).toFloat())
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.dp(CAL_BAR_HEIGHT_DP) * fillFraction).toInt(),
                    Gravity.BOTTOM,
                ),
            )
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(track, LinearLayout.LayoutParams(context.dp(CAL_BAR_WIDTH_DP), context.dp(CAL_BAR_HEIGHT_DP)))
        addView(
            makeText(
                context,
                day.count.toString(),
                Type.LABEL_LARGE,
                colors.onSurface,
            ).apply { setPadding(0, context.dp(Spacing.XS), 0, 0) },
        )
        addView(
            makeText(
                context,
                if (day.isToday) "Now" else weekday.format(Date(day.startsAt)),
                Type.LABEL_SMALL,
                if (day.isToday) colors.primary else colors.onSurfaceVariant,
            ),
        )
        contentDescription = "${day.count} on ${if (day.isToday) "today" else weekday.format(Date(day.startsAt))}"
    }
}

private const val ROW_MIN_HEIGHT_DP = 72
private const val ROW_COVER_WIDTH_DP = 40
private const val ACTION_BOX_DP = 44
private const val SPINNER_DP = 22
private const val COMPACT_BUTTON_DP = 36
private const val CAL_BAR_WIDTH_DP = 30
private const val CAL_BAR_HEIGHT_DP = 46
private const val MIN_BAR_FRACTION = 0.08f
private const val TRACK_ALPHA = 0x0D
private const val FILL_ALPHA = 0x73
