package com.vinicius741.webnovelarchiver.feature.updates

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.UpdatesCopy
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import com.vinicius741.webnovelarchiver.operations.SyncDownloadPlanning
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

/** What the forecast card and the Sync all block ask Updates to do. */
internal interface UpdatesLeadActions {
    fun onFilter(filter: UpdatesFilter)

    fun onSyncAll()

    fun onStop()

    fun onReview()
}

/**
 * The overview's forecast card: how many novels may have new chapters, what else is expected soon,
 * the 7-day release calendar, and the "sync all" / "to download" actions (or the running batch).
 */
internal fun Context.makeForecastCard(
    overview: Overview,
    syncs: UpdatesSyncsState,
    actions: UpdatesLeadActions,
): View {
    val app = this
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
    makePendingReviews(syncs, actions)?.let { card.addView(it, topGap(Spacing.LG)) }
    val batch = syncs.batch
    if (batch != null) {
        card.addView(makeBatchProgress(batch, actions), topGap(Spacing.LG))
        return card
    }
    val buttons = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    val sync = makeButton(app, syncAllLabel(mayBeOut), Btn.FILLED, R.drawable.wna_sync) { actions.onSyncAll() }
    if (mayBeOut == 0) disableButton(sync)
    buttons.addView(sync)
    val toDownload = overview.count(Section.TO_DOWNLOAD)
    if (toDownload > 0) {
        buttons.addView(
            makeButton(
                app,
                "$toDownload to download",
                Btn.SURFACE,
                R.drawable.wna_download,
            ) { actions.onFilter(UpdatesFilter.TO_DOWNLOAD) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    dp(Spacing.SM + 2)
            },
        )
    }
    card.addView(buttons, topGap(Spacing.LG))
    if (mayBeOut > 0) card.addView(makeText(app, SYNC_DOWNLOADS_NOTE, Type.BODY_SMALL, colors.onSurfaceVariant), topGap(Spacing.SM))
    syncs.lastResult?.let { result ->
        card.addView(makeText(app, result, Type.BODY_SMALL, colors.tertiary), topGap(Spacing.MD))
    }
    return card
}

/** The "May be out" filter's lead: the same Sync all as the forecast card, or the running batch's progress. */
internal fun Context.makeSyncAllBlock(
    count: Int,
    syncs: UpdatesSyncsState,
    actions: UpdatesLeadActions,
): View {
    val app = this
    val batch = syncs.batch
    if (batch != null) return makeBatchProgress(batch, actions)
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        addView(makeFullWidthButton(app, syncAllLabel(count), Btn.FILLED, R.drawable.wna_sync, 0) { actions.onSyncAll() })
        addView(makeText(app, SYNC_DOWNLOADS_NOTE, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant), topGap(Spacing.SM))
    }
}

private fun syncAllLabel(count: Int): String = if (count > 0) "Sync all $count" else "Sync all"

/** What a sync does besides checking, said where the button is. */
private val SYNC_DOWNLOADS_NOTE =
    "Syncing also downloads new chapters (up to ${SyncDownloadPlanning.AUTO_DOWNLOAD_LIMIT} a novel; bigger updates ask first)."

/** A finished run's large-update reviews and source check that waited for Updates, with a Review action. */
private fun Context.makePendingReviews(
    syncs: UpdatesSyncsState,
    actions: UpdatesLeadActions,
): View? {
    val app = this
    if (syncs.pendingReviews == 0 && !syncs.pendingBlocked) return null
    val note = UpdatesCopy.pendingReviews(syncs.pendingReviews, blockedSource = syncs.pendingBlocked)
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            makeText(app, note, Type.BODY_MEDIUM, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            makeButton(app, "Review", Btn.TONAL) { actions.onReview() }.apply {
                minHeight = dp(STOP_HEIGHT_DP)
                minimumHeight = dp(STOP_HEIGHT_DP)
                setPadding(dp(Spacing.MD), 0, dp(Spacing.MD), 0)
            },
        )
    }
}

/** Live progress of a "Sync all" run with a Stop action; stopping lets the current novel finish. */
private fun Context.makeBatchProgress(
    batch: UpdatesBatchProgress,
    actions: UpdatesLeadActions,
): View {
    val app = this
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
                        makeButton(app, "Stop", Btn.TEXT) { actions.onStop() }.apply {
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

private fun Context.topGap(gapDp: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(gapDp) }

private const val HEADLINE_SP = 21f
private const val BATCH_SPINNER_DP = 20
private const val STOP_HEIGHT_DP = 36
private const val PROGRESS_HEIGHT_DP = 6
