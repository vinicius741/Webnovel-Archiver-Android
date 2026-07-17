package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.PatreonStats
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.circularRipple
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Patreon support snapshot for the creator behind a story. Sits below the primary action buttons
 * so it reads as supplementary context (not a call to action). The card has two tap targets: the
 * trailing open-in-external glyph in the header opens the creator's Patreon page when [patreonUrl]
 * is set, and the stats body (when [stats] are present) opens the per-novel Trends screen via
 * [onShowTrends] so the user can see members/earnings change over time.
 *
 * The card is rendered whenever the story has a [patreonUrl]. When [stats] are available they fill
 * the divider + two-number body + footer; when [stats] is null the card still appears as a plain
 * link-only surface — surfacing that the creator has a Patreon even when the public stats could not
 * be fetched, instead of silently showing nothing (which would be indistinguishable from no Patreon).
 */
internal fun ScreenHost.buildPatreonStatsCard(
    stats: PatreonStats?,
    patreonUrl: String?,
    onShowTrends: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val clickable = !patreonUrl.isNullOrBlank()
    return makeCard(app).apply {
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.MD)
                bottomMargin = dp(Space.MD)
            }
        // ---- Header: tinted icon disc + "Patreon" title, trailing open affordance when tappable ----
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(app).apply {
                        gravity = Gravity.CENTER
                        background = roundedBg(colors.primaryContainer, dp(14).toFloat())
                        addView(
                            ImageView(app).apply {
                                setImageDrawable(app.tintedIcon(R.drawable.wna_star, colors.onPrimaryContainer))
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                            },
                            LinearLayout.LayoutParams(dp(18), dp(18)),
                        )
                    },
                    LinearLayout.LayoutParams(dp(28), dp(28)),
                )
                addView(
                    makeText(app, "Patreon", Type.TITLE_SMALL, colors.onSurface).apply {
                        letterSpacing = 0.04f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(Space.SM + 2)
                    },
                )
                if (clickable) {
                    addView(
                        ImageView(app).apply {
                            contentDescription = "Open this creator's Patreon"
                            setImageDrawable(app.tintedIcon(R.drawable.wna_open_external, colors.onSurfaceVariant))
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            // Pad out to a comfortable square tap target (40dp), matching
                            // iconButtonSmall elsewhere in the app. OVAL mask so the press
                            // feedback reads as a round highlight instead of a square block.
                            setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
                            background = circularRipple(colors.onSurface)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(patreonUrl)))
                            }
                        },
                        LinearLayout.LayoutParams(dp(40), dp(40)),
                    )
                }
            },
        )
        if (stats != null) {
            addView(makeDivider(app))
            // ---- Stats: two big numbers side by side. Tapping the body opens the Patreon trends
            // (members + monthly earnings over time). The header's open-external glyph still opens
            // the creator's Patreon page, so the two tap targets stay distinct. ----
            addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(Space.XS), 0, dp(Space.SM))
                    isClickable = true
                    isFocusable = true
                    background = selectableRipple(colors.onSurface)
                    contentDescription = "Patreon stats. Tap to view trends."
                    setOnClickListener { onShowTrends() }
                    addView(
                        buildPatreonStat(
                            if (stats.membersIsEstimated) "Est. paid members" else "Paid members",
                            formatMemberCount(stats),
                        ),
                        statLayoutParams(),
                    )
                    addView(
                        buildPatreonStat(
                            if (stats.amountIsEstimated) "Estimated amount" else "Monthly earnings",
                            formatMonthlyUsd(stats.monthlyUsdCents),
                        ),
                        statLayoutParams(),
                    )
                },
            )
            // ---- Footer ----
            if (stats.updatedAt > 0) {
                addView(
                    makeText(
                        app,
                        "Updated ${formatPatreonDate(stats.updatedAt)} · per month · tap for trends",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ),
                )
            }
        }
        isClickable = false
        isFocusable = false
        contentDescription = null
    }
}

private fun ScreenHost.buildPatreonStat(
    label: String,
    value: String,
): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            makeText(app, value, Type.TITLE_LARGE, ThemeManager.colors.onSurface).apply {
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        addView(
            makeText(app, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(2), 0, 0)
            },
        )
    }

private fun formatMonthlyUsd(cents: Long): String {
    val dollars = (cents / 100.0).roundToLong()
    return "$${NumberFormat.getIntegerInstance(Locale.US).format(dollars)}"
}

private fun formatMemberCount(stats: PatreonStats): String {
    val prefix = if (stats.membersIsEstimated) "~" else ""
    return "$prefix${NumberFormat.getIntegerInstance().format(stats.paidMembers)}"
}

private fun formatPatreonDate(timestamp: Long): String =
    DateTimeFormatter
        .ofPattern("MMM d, yyyy", Locale.US)
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

private fun statLayoutParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
