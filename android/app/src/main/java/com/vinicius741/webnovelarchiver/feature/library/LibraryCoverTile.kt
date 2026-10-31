package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
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
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Bare tile root for the cover grid; the cover itself is the visual surface, so no card chrome. */
internal fun makeLibraryCoverTile(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Leaves room for the selection outline around the cover's rounded corners.
        val inset = context.dp(Space.XS)
        setPadding(inset, inset, inset, inset)
    }

/** Cover-first tile: full-width 2:3 cover, then a compact title, author, and download progress. */
internal fun ScreenHost.bindLibraryCoverTile(
    tile: LinearLayout,
    story: Story,
) {
    tile.removeAllViews()
    val selected = if (libraryScreenState.selectedStoryIds.isEmpty()) null else story.id in libraryScreenState.selectedStoryIds
    val frame = CoverAspectFrame(app)
    frame.addView(
        coverImage(story, COVER_SOURCE_WIDTH_DP, COVER_SOURCE_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        },
    )
    if (story.isArchived == true) frame.addView(archivedBadge())
    if (selected != null) addSelectionBadge(frame)

    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                makeText(app, story.title, Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(Space.SM), 0, 0)
                },
            )
            addView(
                makeText(app, story.author, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
    wireLibraryStoryContent(tile, content, story, selected)
    // The opaque cover would hide a background ripple; draw the press feedback over the whole tile.
    content.foreground = content.background
    content.background = null
    // Extra row height goes to the text block, so progress bars line up across a row.
    tile.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (story.totalChapters > 0) tile.addView(makeLibraryProgressSummary(story, topMarginDp = Space.SM))
}

private fun ScreenHost.archivedBadge(): ImageView {
    val size = dp(28)
    val inset = dp(Space.SM)
    return ImageView(app).apply {
        contentDescription = "Archived"
        setImageDrawable(app.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Space.XS + 2), dp(Space.XS + 2), dp(Space.XS + 2), dp(Space.XS + 2))
        background = roundedBg(ColorUtils.setAlphaComponent(ThemeManager.colors.surface, ARCHIVED_SCRIM_ALPHA), size / 2f)
        layoutParams =
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(0, inset, inset, 0)
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
private const val ARCHIVED_SCRIM_ALPHA = 0xD9
