package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Same vertical padding [makeBadge] gives its pills. */
private const val BADGE_VERTICAL_PADDING_DP = 3
private const val NEW_CHAPTER_DESCRIPTION = "Next chapter may be out, sync to check"

/**
 * The predicted next chapter came due after the last chapter sync, so syncing would likely find it.
 * Late authors (already checked well past the usual gap) are not flagged.
 */
internal fun mayHaveNewChapter(story: Story): Boolean = ReleaseCadencePlanning.mayHaveNewChapter(story)

/** List-card row: source name, publication status, availability, and the new-chapter hint; null when empty. */
internal fun ScreenHost.libraryBadgeRow(story: Story): View? {
    val items =
        listOfNotNull(
            SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.let {
                makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                    // Matches the pills' box so the top-aligned wrap row keeps the label centered.
                    includeFontPadding = false
                    setPadding(0, dp(BADGE_VERTICAL_PADDING_DP), 0, dp(BADGE_VERTICAL_PADDING_DP))
                }
            },
            publicationStatusBadge(story),
            sourceAvailabilityBadge(story),
            if (mayHaveNewChapter(story)) newChapterBadge() else null,
        )
    if (items.isEmpty()) return null
    // Wraps rather than squeezing the last pill when a narrow card cannot fit every badge.
    return WrapLayout(app).apply {
        horizontalSpacingDp = Space.SM
        verticalSpacingDp = Space.XS
        setPadding(0, dp(Space.XS), 0, 0)
        items.forEach(::addView)
    }
}

/** Small text-free refresh glyph for the list card's badge row; sized to the pills' line height. */
private fun ScreenHost.newChapterBadge(): View {
    val size = dp(LIST_ICON_BOX_DP)
    val pad = dp(LIST_ICON_PADDING_DP)
    return ImageView(app).apply {
        contentDescription = NEW_CHAPTER_DESCRIPTION
        setImageDrawable(app.tintedIcon(R.drawable.wna_refresh, ThemeManager.colors.primary))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.MarginLayoutParams(size, size)
    }
}

/** Small top-end refresh glyph on a cover-grid tile, on a translucent disc so it reads on any cover. */
internal fun ScreenHost.newChapterCoverBadge(): ImageView {
    val size = dp(COVER_ICON_BOX_DP)
    val inset = dp(Space.XS + 2)
    val pad = dp(LIST_ICON_PADDING_DP + 1)
    val colors = ThemeManager.colors
    return ImageView(app).apply {
        contentDescription = NEW_CHAPTER_DESCRIPTION
        setImageDrawable(app.tintedIcon(R.drawable.wna_refresh, colors.primary))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(pad, pad, pad, pad)
        background = roundedBg(ColorUtils.setAlphaComponent(colors.surface, COVER_DISC_ALPHA), size / 2f)
        layoutParams =
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(0, inset, inset, 0)
            }
    }
}

private const val LIST_ICON_BOX_DP = 18
private const val LIST_ICON_PADDING_DP = 2
private const val COVER_ICON_BOX_DP = 20
private const val COVER_DISC_ALPHA = 0xB3
