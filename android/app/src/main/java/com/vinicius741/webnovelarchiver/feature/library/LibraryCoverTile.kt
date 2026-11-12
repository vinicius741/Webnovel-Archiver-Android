package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Bare tile root for the cover grid; the cover itself is the visual surface, so no card chrome. */
internal fun makeLibraryCoverTile(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = context.dp(2)
        setPadding(inset, inset, inset, inset)
    }

/**
 * Cover-first tile: the whole 2:3 cover with only small corner badges on the art, the chapter
 * coverage capsule below it (never over the art), then title and author.
 */
internal fun ScreenHost.bindLibraryCoverTile(
    tile: LinearLayout,
    story: Story,
) {
    tile.removeAllViews()
    val selected = librarySelectionState(story)
    val frame = CoverAspectFrame(app)
    frame.addView(
        coverImage(story, COVER_SOURCE_WIDTH_DP, COVER_SOURCE_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        },
    )
    val corner = mutableListOf<ImageView>()
    if (mayHaveNewChapter(story)) corner += cornerBadge(R.drawable.wna_sync, ThemeManager.colors.primary, NEW_CHAPTER_DESCRIPTION)
    if (hasUnseenAiResult(story)) {
        corner +=
            cornerBadge(R.drawable.wna_auto_awesome, ThemeManager.colors.onPrimaryContainer, "New AI cover ready")
    }
    corner.forEachIndexed { index, badge ->
        (badge.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(Spacing.XS + 2) + index * dp(CORNER_BADGE_DP + Spacing.XS)
        frame.addView(badge)
    }
    if (selected != null) frame.addView(selectionCheck(selected))
    if (selected == true) {
        frame.foreground = strokeBg(Color.TRANSPARENT, dp(COVER_RADIUS_DP).toFloat(), ThemeManager.colors.primary, dp(SELECTED_OUTLINE_DP))
    }
    tile.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (story.totalChapters > 0) {
        tile.addView(
            makeLibraryCoverageBar(story),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), dp(Spacing.SM), dp(2), 0)
            },
        )
    }
    tile.addView(
        makeText(app, story.title, Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
            textSize = TILE_TITLE_SP
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(Spacing.SM), dp(2), 0)
        },
    )
    tile.addView(
        makeText(app, story.author, Type.BODY_SMALL, ThemeManager.colors.faint).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        },
    )
    wireLibraryStoryContent(tile, story, selected)
}

/** A finished background AI cover nobody has looked at yet (persisted; cleared on opening AI Controls). */
internal fun ScreenHost.hasUnseenAiResult(story: Story): Boolean = story.id in repository.aiCoverUnseen.value

/** Bottom-end sparkle on a list card's cover marking an unseen AI result. */
internal fun ScreenHost.aiResultBadge(): ImageView {
    val size = dp(CORNER_BADGE_DP)
    val inset = dp(Spacing.XS + 2)
    return ImageView(app).apply {
        contentDescription = "New AI cover ready"
        setImageDrawable(app.tintedIcon(R.drawable.wna_auto_awesome, ThemeManager.colors.onPrimaryContainer))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Spacing.XS + 1), dp(Spacing.XS + 1), dp(Spacing.XS + 1), dp(Spacing.XS + 1))
        background = roundedBg(ColorUtils.setAlphaComponent(BADGE_DISC, BADGE_DISC_ALPHA), size / 2f)
        layoutParams =
            FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, inset, inset)
            }
    }
}

/** A small icon on a dark disc at the cover's top-end corner. */
private fun ScreenHost.cornerBadge(
    iconRes: Int,
    tint: Int,
    description: String,
): ImageView {
    val size = dp(CORNER_BADGE_DP)
    return ImageView(app).apply {
        contentDescription = description
        setImageDrawable(app.tintedIcon(iconRes, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Spacing.XS + 1), dp(Spacing.XS + 1), dp(Spacing.XS + 1), dp(Spacing.XS + 1))
        background = roundedBg(ColorUtils.setAlphaComponent(BADGE_DISC, BADGE_DISC_ALPHA), size / 2f)
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { topMargin = dp(Spacing.XS + 2) }
    }
}

/** The round check on a cover's top-start corner in selection mode: hollow, or gold with a check. */
internal fun ScreenHost.selectionCheck(selected: Boolean): ImageView {
    val colors = ThemeManager.colors
    val size = dp(CHECK_DP)
    return ImageView(app).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(3), dp(3), dp(3), dp(3))
        if (selected) {
            background = roundedBg(colors.primary, size / 2f)
            setImageDrawable(app.tintedIcon(R.drawable.wna_check, colors.onPrimary))
            contentDescription = "Selected"
        } else {
            background =
                strokeBg(
                    ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, IDLE_CHECK_ALPHA),
                    size / 2f,
                    android.graphics.Color.WHITE,
                    dp(2),
                )
            contentDescription = "Not selected"
        }
        layoutParams =
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(Spacing.XS + 2), dp(Spacing.XS + 2), 0, 0)
            }
    }
}

/** Sizes itself to its width at a 2:3 book-cover ratio, so tiles scale with the column width. */
private class CoverAspectFrame(
    context: Context,
) : FrameLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 3 / 2, MeasureSpec.EXACTLY),
        )
    }
}

/** Nominal size handed to the cover builder; the frame overrides it with the real tile size. */
private const val COVER_SOURCE_WIDTH_DP = LibraryViewModePlanning.MIN_COVER_TILE_WIDTH
private const val CORNER_BADGE_DP = 26
private const val CHECK_DP = 24
private const val IDLE_CHECK_ALPHA = 0x73
private const val COVER_RADIUS_DP = 12
private const val SELECTED_OUTLINE_DP = 3
private const val TILE_TITLE_SP = 13f
private const val BADGE_DISC = 0xFF0A0805.toInt()
private const val BADGE_DISC_ALPHA = 0xB3
