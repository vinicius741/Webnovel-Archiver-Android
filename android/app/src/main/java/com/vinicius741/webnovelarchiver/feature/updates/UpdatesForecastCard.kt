package com.vinicius741.webnovelarchiver.feature.updates

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.UpdatesCopy
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEyebrow
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeSmallSpinner
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTintedCard

/**
 * The overview's forecast card: how many novels may have new chapters, what else is expected soon,
 * the 7-day release calendar, and the "sync all" / "to download" actions (or the running batch).
 */
internal fun ScreenHost.makeForecastCard(
    overview: Overview,
    onFilter: (UpdatesFilter) -> Unit,
): View {
    val colors = ThemeManager.colors
    val mayBeOut = overview.count(Section.MAY_BE_OUT)
    val late = overview.count(Section.RUNNING_LATE)
    val card = makeTintedCard(app)
    card.addView(makeEyebrow(app, "Release forecast"))
    val headline =
        when (mayBeOut) {
            0 -> "You're all caught up"
            1 -> "1 novel may have a new chapter"
            else -> "$mayBeOut novels may have new chapters"
        }
    card.addView(
        makeText(app, headline, Type.TITLE_LARGE, colors.onSurface).apply {
            textSize = HEADLINE_SP
            setPadding(0, dp(Spacing.XS + 2), 0, dp(Spacing.XS))
        },
    )
    val soonLine =
        listOfNotNull(
            "${overview.expectedWithinTwoDays} expected in the next 2 days",
            if (late > 0) "$late running late" else null,
        ).joinToString(" · ")
    card.addView(makeText(app, soonLine, Type.BODY_MEDIUM, colors.onSurfaceVariant))
    card.addView(
        makeReleaseCalendar(app, overview.calendar),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin =
                dp(Spacing.LG)
        },
    )
    makePendingReviews()?.let { card.addView(it, topGap(Spacing.LG)) }
    val batch = updatesState.batch
    if (batch != null) {
        card.addView(makeBatchProgress(batch), topGap(Spacing.LG))
        return card
    }
    val actions = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    val sync = makeButton(app, if (mayBeOut > 0) "Sync $mayBeOut" else "Sync", Btn.FILLED, R.drawable.wna_sync) { syncAllMayBeOut() }
    if (mayBeOut == 0) disableButton(sync)
    actions.addView(sync)
    val toDownload = overview.count(Section.TO_DOWNLOAD)
    if (toDownload > 0) {
        actions.addView(
            makeButton(app, "$toDownload to download", Btn.SURFACE, R.drawable.wna_download) { onFilter(UpdatesFilter.TO_DOWNLOAD) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    dp(Spacing.SM + 2)
            },
        )
    }
    card.addView(actions, topGap(Spacing.LG))
    updatesState.lastResult?.let { result ->
        card.addView(makeText(app, result, Type.BODY_SMALL, colors.tertiary), topGap(Spacing.MD))
    }
    return card
}

/** The "May be out" filter's lead action: sync them all, or the running batch's progress. */
internal fun ScreenHost.makeSyncAllBlock(count: Int): View {
    val batch = updatesState.batch
    if (batch != null) return makeBatchProgress(batch)
    return makeFullWidthButton(app, "Sync all $count", Btn.TONAL, R.drawable.wna_sync, 0) { syncAllMayBeOut() }
}

/** A finished run's large-update reviews and source check that waited for Updates, with a Review action. */
private fun ScreenHost.makePendingReviews(): View? {
    val state = updatesState
    if (state.pendingReviews.isEmpty() && state.pendingBlocked == null) return null
    val note = UpdatesCopy.pendingReviews(state.pendingReviews.size, blockedSource = state.pendingBlocked != null)
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            makeText(app, note, Type.BODY_MEDIUM, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            makeButton(app, "Review", Btn.TONAL) { showPendingUpdatesReviews() }.apply {
                minHeight = dp(STOP_HEIGHT_DP)
                minimumHeight = dp(STOP_HEIGHT_DP)
                setPadding(dp(Spacing.MD), 0, dp(Spacing.MD), 0)
            },
        )
    }
}

/** Live progress of a "Sync all" run with a Stop action; stopping lets the current novel finish. */
private fun ScreenHost.makeBatchProgress(batch: UpdatesBatch): View {
    val colors = ThemeManager.colors
    val current = (batch.checked + 1).coerceAtMost(batch.total)
    val line =
        if (batch.stopping) {
            "Stopping after this novel…"
        } else {
            listOfNotNull("Checking $current of ${batch.total}", batch.currentTitle).joinToString(" · ")
        }
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(makeSmallSpinner(app, BATCH_SPINNER_DP))
                addView(
                    makeText(app, line, Type.BODY_MEDIUM, colors.onSurface).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Spacing.SM + 2) },
                )
                if (!batch.stopping) {
                    addView(
                        makeButton(app, "Stop", Btn.TEXT) { stopUpdatesBatch() }.apply {
                            minHeight = dp(STOP_HEIGHT_DP)
                            minimumHeight = dp(STOP_HEIGHT_DP)
                            setPadding(dp(Spacing.MD), 0, dp(Spacing.MD), 0)
                            contentDescription = "Stop syncing"
                        },
                    )
                }
            },
        )
        addView(
            makeProgress(app, batch.checked.toFloat() / batch.total.coerceAtLeast(1)).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PROGRESS_HEIGHT_DP)).apply {
                        topMargin =
                            dp(Spacing.SM + 2)
                    }
            },
        )
    }
}

private fun ScreenHost.topGap(gapDp: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(gapDp) }

private const val HEADLINE_SP = 21f
private const val BATCH_SPINNER_DP = 20
private const val STOP_HEIGHT_DP = 36
private const val PROGRESS_HEIGHT_DP = 6
