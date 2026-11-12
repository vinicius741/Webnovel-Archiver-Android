package com.vinicius741.webnovelarchiver.feature.library

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.formatScore
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.tintedIcon

internal const val NEW_CHAPTER_DESCRIPTION = "Next chapter may be out, sync to check"

/** Same vertical padding the status pills use, so a text-only item lines up with them. */
private const val PILL_VERTICAL_PADDING_DP = 3

/**
 * The next chapter may be out: the same rule as the Updates "May be out" list and its badge, so
 * the Library and Updates never disagree.
 */
internal fun mayHaveNewChapter(story: Story): Boolean {
    val now = System.currentTimeMillis()
    return UpdatesPlanning.mayBeOut(ReleaseCadencePlanning.forecast(story, now), story.lastChapterSyncAt, now)
}

/** List-card meta row: source, publication status, availability, score, and the "New?" hint; null when empty. */
internal fun ScreenHost.libraryBadgeRow(story: Story): View? {
    val colors = ThemeManager.colors
    val items =
        listOfNotNull(
            SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.let {
                makeText(app, it.name, Type.LABEL_MEDIUM, colors.primary).apply {
                    includeFontPadding = false
                    setPadding(0, dp(PILL_VERTICAL_PADDING_DP), 0, dp(PILL_VERTICAL_PADDING_DP))
                }
            },
            publicationStatusBadge(story),
            sourceAvailabilityBadge(story),
            story.score?.takeIf { it.isNotBlank() }?.let { inlineScore(it) },
            if (mayHaveNewChapter(story)) newChapterPill() else null,
        )
    if (items.isEmpty()) return null
    // Wraps rather than squeezing the last pill when a narrow card cannot fit every badge.
    return WrapLayout(app).apply {
        horizontalSpacingDp = Spacing.SM
        verticalSpacingDp = Spacing.XS
        setPadding(0, dp(Spacing.SM - 2), 0, 0)
        items.forEach(::addView)
    }
}

/** "★ 4.74": a gold star and the score in bold. */
private fun ScreenHost.inlineScore(score: String): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(PILL_VERTICAL_PADDING_DP), 0, dp(PILL_VERTICAL_PADDING_DP))
        addView(
            android.widget.ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_star, ThemeManager.colors.primary)) },
            LinearLayout.LayoutParams(dp(STAR_DP), dp(STAR_DP)).apply { marginEnd = dp(2) },
        )
        addView(
            makeText(app, formatScore(score), Type.LABEL_MEDIUM, ThemeManager.colors.onSurface).apply { includeFontPadding = false },
        )
        contentDescription = "Score ${formatScore(score)}"
    }

/** Gold "New?" pill with a sync glyph: the next chapter may be out. */
private fun ScreenHost.newChapterPill(): View {
    val colors = ThemeManager.colors
    return TextView(app).apply {
        text = "New?"
        contentDescription = NEW_CHAPTER_DESCRIPTION
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_SMALL.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(colors.onPrimaryContainer)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Spacing.SM), dp(PILL_VERTICAL_PADDING_DP), dp(Spacing.SM), dp(PILL_VERTICAL_PADDING_DP))
        background = roundedBg(colors.primaryContainer, dp(Spacing.SM + 2).toFloat())
        app.tintedIcon(R.drawable.wna_sync, colors.onPrimaryContainer)?.let { icon ->
            val size = dp(PILL_ICON_DP)
            icon.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(3)
        }
    }
}

private const val STAR_DP = 14
private const val PILL_ICON_DP = 12
