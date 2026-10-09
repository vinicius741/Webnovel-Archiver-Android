package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.circularRipple
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
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
 * is set, and the stats body (when raw stats produce figures) opens the per-novel Trends screen via
 * [onShowTrends] so the user can see members/earnings change over time.
 *
 * The card is rendered whenever the story has a [patreonUrl]. [stats] holds only measured data;
 * the displayed dollar figure (exact or estimated, single value or floor–estimate range) is
 * derived at render, so a formula fix shows through without a refetch. When stats yield nothing
 * displayable the card still appears as a plain link-only surface — surfacing that the creator has
 * a Patreon even when the public stats could not be fetched.
 */
internal fun ScreenHost.buildPatreonStatsCard(
    stats: PatreonRawStats?,
    patreonUrl: String?,
    earlyAccess: PatreonEarlyAccessPlanning.Display,
    onShowTrends: () -> Unit,
    onEarlyAccess: () -> Unit,
    onEarlyAccessPrompt: (PatreonEarlyAccessPlanning.Action) -> Unit,
    earlyAccessEnabled: Boolean = true,
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
        val earnings = stats?.let(PatreonEarningsPlanning::estimate)
        if (stats != null && earnings != null) {
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
                            if (earnings.membersIsEstimated) "Est. paid members" else "Paid members",
                            formatMemberCount(earnings),
                        ),
                        statLayoutParams(),
                    )
                    // Hidden earnings with no usable tier ladder: members only, no dollar figure.
                    earnings.monthlyUsdCents?.let { monthly ->
                        addView(
                            buildPatreonStat(
                                if (earnings.amountIsEstimated) "Estimated amount" else "Monthly earnings",
                                formatMonthlyUsd(monthly, earnings.floorUsdCents),
                            ),
                            statLayoutParams(),
                        )
                    }
                },
            )
            // ---- Footer ----
            if (stats.capturedAt > 0) {
                addView(
                    makeText(
                        app,
                        "Updated ${formatPatreonDate(stats.capturedAt)} · per month · tap for trends",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ),
                )
            }
        }
        addView(makeDivider(app))
        addEarlyAccessSection(this, earlyAccess, earlyAccessEnabled, onEarlyAccess, onEarlyAccessPrompt)
        isClickable = false
        isFocusable = false
        contentDescription = null
    }
}

/** Which Patreon collection supplies chapters ahead of the public release, plus its one next step. */
private fun ScreenHost.addEarlyAccessSection(
    card: LinearLayout,
    earlyAccess: PatreonEarlyAccessPlanning.Display,
    earlyAccessEnabled: Boolean,
    onEarlyAccess: () -> Unit,
    onEarlyAccessPrompt: (PatreonEarlyAccessPlanning.Action) -> Unit,
) {
    with(card) {
        val colors = ThemeManager.colors
        val earlyAccessSummary = earlyAccess.summary
        // ---- Early access: which Patreon collection supplies chapters ahead of the public release ----
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
                // Relinking drops stored copies, so it waits until the running story operation (e.g. a sync) ends.
                isEnabled = earlyAccessEnabled
                isClickable = earlyAccessEnabled
                isFocusable = earlyAccessEnabled
                alpha = if (earlyAccessEnabled) 1f else 0.4f
                background = selectableRipple(colors.onSurface)
                contentDescription =
                    if (earlyAccessEnabled) {
                        "Early-access chapters: $earlyAccessSummary. Tap to choose a collection."
                    } else {
                        "Early-access chapters: $earlyAccessSummary."
                    }
                if (earlyAccessEnabled) setOnClickListener { onEarlyAccess() }
                addView(makeText(app, "Early-access chapters", Type.LABEL_LARGE, colors.onSurface))
                addView(makeText(app, earlyAccessSummary, Type.BODY_SMALL, colors.onSurfaceVariant))
            },
        )
        // ---- The one next step (sign in, check again, or see tiers), kept outside the row's tap target ----
        earlyAccess.prompt?.let { prompt ->
            prompt.hint?.let { hint ->
                addView(
                    makeText(app, hint, Type.BODY_SMALL, colors.onSurfaceVariant),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.XS)
                    },
                )
            }
            // A check is a sync, so it waits for the running story operation like relinking does.
            val enabled = earlyAccessEnabled || prompt.action != PatreonEarlyAccessPlanning.Action.CHECK_NOW
            addView(
                makeFullWidthButton(
                    app,
                    prompt.buttonLabel,
                    if (prompt.action == PatreonEarlyAccessPlanning.Action.OPEN_PATREON) Btn.OUTLINED else Btn.TONAL,
                    enabled = enabled,
                ) { onEarlyAccessPrompt(prompt.action) }.apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Space.SM)
                },
            )
        }
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

private fun formatMonthlyUsd(
    cents: Long,
    floorCents: Long? = null,
): String {
    val format = NumberFormat.getIntegerInstance(Locale.US)
    val dollars = { c: Long -> "$${format.format((c / 100.0).roundToLong())}" }
    return floorCents?.let { "${dollars(it)}–${dollars(cents)}" } ?: dollars(cents)
}

private fun formatMemberCount(earnings: PatreonEarningsPlanning.PatreonEarnings): String {
    val prefix = if (earnings.membersIsEstimated) "~" else ""
    return "$prefix${NumberFormat.getIntegerInstance().format(earnings.paidMembers)}"
}

private fun formatPatreonDate(timestamp: Long): String =
    DateTimeFormatter
        .ofPattern("MMM d, yyyy", Locale.US)
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

private fun statLayoutParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
