package com.vinicius741.webnovelarchiver.feature.trending

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Entry
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Reading
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEyebrow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTintedCard
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Builds Trending's hero, rows and footer for one render. */
internal class TrendingViews(
    private val host: ScreenHost,
) {
    private val app = host.app

    private fun dp(value: Int): Int = app.dp(value)

    /** The #1 novel: a tinted card with a large cover, the lead rise, a wide sparkline and the other rising signals. */
    fun hero(entry: Entry): View {
        val colors = ThemeManager.colors
        val card = makeTintedCard(app)
        card.addView(makeEyebrow(app, "#1 trending"))
        val top =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(Spacing.MD), 0, 0)
            }
        top.addView(
            host.coverImage(entry.story, widthDp = HERO_COVER_WIDTH_DP, heightDp = HERO_COVER_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(Spacing.LG)
            },
        )
        val text =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    makeText(app, entry.story.title, Type.TITLE_LARGE, colors.onSurface).apply {
                        textSize = HERO_TITLE_SP
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                entry.story.author
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { author ->
                        addView(
                            makeText(app, author, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setPadding(0, dp(2), 0, 0)
                            },
                        )
                    }
                addView(leadLine(app, TrendingCopy.leadLine(entry.lead), Type.BODY_MEDIUM), topMargin(app, Spacing.SM))
                addView(readingLine(app, entry.reading, Type.BODY_SMALL), topMargin(app, Spacing.XS))
            }
        top.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        card.addView(
            TrendSparkline(app, colors.primary, strokeDp = 2.5f).apply { setPoints(entry.lead.points) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HERO_SPARK_HEIGHT_DP)).apply { topMargin = dp(Spacing.MD) },
        )
        card.addView(
            makeText(app, "${entry.lead.label} · last ${TrendingPlanning.SPARKLINE_DAYS} days", Type.LABEL_SMALL, colors.faint).apply {
                setPadding(0, dp(Spacing.XS), 0, 0)
            },
        )
        if (entry.supporting.isNotEmpty()) {
            card.addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    entry.supporting.take(MAX_HERO_PILLS).forEachIndexed { index, movement ->
                        addView(
                            pill(app, TrendingCopy.chip(movement)),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                if (index > 0) marginStart = dp(Spacing.SM)
                            },
                        )
                    }
                },
                topMargin(app, Spacing.MD),
            )
        }
        card.foreground = selectableRipple(colors.onSurface)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { host.showDetails(entry.story.id) }
        return card
    }

    /** A ranked row: rank, cover, title, the lead rise, reading progress with other rises, and a sparkline. */
    fun row(
        entry: Entry,
        rank: Int,
    ): View {
        val colors = ThemeManager.colors
        val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
        val row =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ROW_MIN_HEIGHT_DP)
                setPadding(dp(Spacing.SM), dp(Spacing.MD), dp(Spacing.MD), dp(Spacing.MD))
                background = strokeBg(colors.elevation1, radius, colors.line, dp(1).coerceAtLeast(1))
                foreground = selectableRipple(colors.onSurface)
                clipToOutline = true
                isClickable = true
                isFocusable = true
                setOnClickListener { host.showDetails(entry.story.id) }
            }
        row.addView(
            makeText(app, rank.toString(), Type.TITLE_MEDIUM, if (rank <= PODIUM) colors.primary else colors.faint).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(dp(RANK_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        row.addView(
            host.coverImage(entry.story, widthDp = ROW_COVER_WIDTH_DP, heightDp = ROW_COVER_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(Spacing.MD)
            },
        )
        val meta =
            listOf(TrendingCopy.reading(entry.reading)) + entry.supporting.take(MAX_ROW_MENTIONS).map(TrendingCopy::chip)
        row.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    makeText(app, entry.story.title, Type.TITLE_SMALL, colors.onSurface).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                addView(leadLine(app, TrendingCopy.shortLine(entry.lead), Type.BODY_SMALL), topMargin(app, Spacing.XS))
                addView(
                    makeText(app, meta.joinToString(" · "), Type.BODY_SMALL, readingColor(entry.reading)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            TrendSparkline(app, colors.primary).apply { setPoints(entry.lead.points) },
            LinearLayout.LayoutParams(dp(ROW_SPARK_WIDTH_DP), dp(ROW_SPARK_HEIGHT_DP)).apply { marginStart = dp(Spacing.SM) },
        )
        return row
    }

    /** Below the list: how many caught-up novels are hidden, and how the ranking works. */
    fun footer(
        caughtUpHidden: Int,
        measured: Int,
    ): View {
        val colors = ThemeManager.colors
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.SM))
            val lines =
                listOfNotNull(
                    "Compared across $measured novels with recorded stats.",
                    when (caughtUpHidden) {
                        0 -> null
                        1 -> "1 novel you're caught up on is hidden."
                        else -> "$caughtUpHidden novels you're caught up on are hidden."
                    },
                )
            addView(
                makeText(app, lines.joinToString("\n"), Type.BODY_SMALL, colors.onSurfaceVariant).apply { gravity = Gravity.CENTER },
            )
            addView(
                makeButton(app, "How Trending works", Btn.TEXT, R.drawable.wna_info) { showExplanation() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.XS)
                },
            )
        }
    }

    private fun showExplanation() {
        val colors = ThemeManager.colors
        val body =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                EXPLANATION.forEachIndexed { index, (title, text) ->
                    addView(makeText(app, title, Type.TITLE_SMALL, colors.onSurface), topMargin(app, if (index == 0) 0 else Spacing.LG))
                    addView(makeText(app, text, Type.BODY_MEDIUM, colors.onSurfaceVariant), topMargin(app, Spacing.XS))
                }
            }
        showBottomSheet(app, title = "How Trending works", body = body)
    }
}

private val EXPLANATION =
    listOf(
        "What counts" to
            "Each sync records a novel's stats. Trending compares them over time: Patreon patrons (paid members), " +
            "the source's readers (followers, watchers, favorites), total views and the rating.",
        "Fair to every signal" to
            "Each signal is ranked against how the rest of your library moved on it, so Patreon numbers " +
            "can't crowd out the others. Small numbers are smoothed, so 2 to 5 patrons doesn't outrank a novel gaining hundreds.",
        "Rises fade" to
            "A rise counts half as much after ${TrendingPlanning.HALF_LIFE_DAYS.toInt()} days and almost " +
            "nothing after three weeks, and novels that always grow at the same pace are discounted. The " +
            "list keeps changing, and a novel comes back with its next burst.",
        "Your bookmark" to
            "Novels whose last chapter you've bookmarked are left out until a new chapter arrives. " +
            "Sync more often for fresher trends.",
    )

private fun leadLine(
    context: Context,
    text: String,
    type: Type,
): View {
    val colors = ThemeManager.colors
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val iconSize = context.dp(if (type == Type.BODY_MEDIUM) LEAD_ICON_LARGE_DP else LEAD_ICON_DP)
        addView(
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_trending, colors.primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = context.dp(Spacing.XS + 2) },
        )
        addView(
            makeText(context, text, type, colors.primary).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}

private fun readingLine(
    context: Context,
    reading: Reading,
    type: Type,
): TextView =
    makeText(context, TrendingCopy.reading(reading), type, readingColor(reading)).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

/** The bookmark's ivory for novels in progress; muted for ones not started. */
private fun readingColor(reading: Reading): Int =
    when (reading) {
        is Reading.Behind -> ThemeManager.colors.bookmark
        is Reading.NotStarted -> ThemeManager.colors.onSurfaceVariant
    }

private fun pill(
    context: Context,
    label: String,
): TextView {
    val colors = ThemeManager.colors
    return makeText(context, label, Type.LABEL_MEDIUM, colors.onSurface).apply {
        background = roundedBg(colors.elevation3, context.dp(PILL_HEIGHT_DP / 2).toFloat())
        gravity = Gravity.CENTER_VERTICAL
        minHeight = context.dp(PILL_HEIGHT_DP)
        setPadding(context.dp(Spacing.MD), 0, context.dp(Spacing.MD), 0)
        maxLines = 1
    }
}

private fun topMargin(
    context: Context,
    dp: Int,
): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(dp)
    }

private const val HERO_COVER_WIDTH_DP = 76
private const val HERO_TITLE_SP = 19f
private const val HERO_SPARK_HEIGHT_DP = 64
private const val MAX_HERO_PILLS = 2
private const val ROW_MIN_HEIGHT_DP = 96
private const val ROW_COVER_WIDTH_DP = 44
private const val ROW_SPARK_WIDTH_DP = 52
private const val ROW_SPARK_HEIGHT_DP = 32
private const val RANK_WIDTH_DP = 30
private const val PODIUM = 3
private const val MAX_ROW_MENTIONS = 1
private const val LEAD_ICON_DP = 14
private const val LEAD_ICON_LARGE_DP = 16
private const val PILL_HEIGHT_DP = 28
