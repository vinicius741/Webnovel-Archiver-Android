package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.loadStoryCover
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeCoverPlaceholder
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Cover-first tile: the whole 2:3 cover with only small corner badges on the art, the chapter coverage
 * capsule below it (never over the art), then title and author. The views are built once per holder.
 */
internal class LibraryCoverTile(
    private val host: ScreenHost,
) : LibraryCardView {
    private val app = host.app

    /** Bare tile root; the cover itself is the visual surface, so no card chrome. */
    override val root: LinearLayout =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            val inset = app.dp(2)
            setPadding(inset, inset, inset, inset)
            makeLibraryCardTappable(this)
        }
    private val frame = CoverAspectFrame(app)
    private val cover =
        LibraryCoverSlot(host, COVER_SOURCE_WIDTH_DP, COVER_SOURCE_WIDTH_DP * 3 / 2) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    private val newChapterBadge =
        host.cornerBadge(R.drawable.wna_sync, ThemeManager.colors.primary, NEW_CHAPTER_DESCRIPTION).apply {
            (layoutParams as FrameLayout.LayoutParams).marginEnd = app.dp(Spacing.XS + 2)
        }
    private val coverage = ChapterCoverageBar(app)
    private val title =
        makeText(app, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
            textSize = TILE_TITLE_SP
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(app.dp(2), app.dp(Spacing.SM), app.dp(2), 0)
        }
    private val author =
        makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.faint).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(app.dp(2), app.dp(2), app.dp(2), 0)
        }

    init {
        cover.addArtTo(frame)
        frame.addView(newChapterBadge)
        frame.addView(cover.check)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            coverage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(app.dp(2), app.dp(Spacing.SM), app.dp(2), 0)
            },
        )
        root.addView(title)
        root.addView(author)
    }

    override fun bind(
        story: Story,
        events: LibraryCardEvents,
    ) {
        val selected = events.selectionOf(story.id)
        cover.bind(story, selected)
        newChapterBadge.visibility = if (mayHaveNewChapter(story)) View.VISIBLE else View.GONE
        frame.foreground =
            if (selected == true) {
                strokeBg(Color.TRANSPARENT, app.dp(COVER_RADIUS_DP).toFloat(), ThemeManager.colors.primary, app.dp(SELECTED_OUTLINE_DP))
            } else {
                null
            }
        bindLibraryCoverageBar(coverage, story)
        title.text = story.title
        author.text = story.author
        wireLibraryStoryContent(root, story, selected, events)
        remeasureLibraryCard(root)
    }
}

/**
 * A story's cover, built once per card: the art, the no-cover placeholder in its place, and the selection
 * check over its top-start corner. [addArtTo] adds the art; the owner adds [check] above anything else.
 */
internal class LibraryCoverSlot(
    private val host: ScreenHost,
    widthDp: Int,
    heightDp: Int,
    params: () -> FrameLayout.LayoutParams,
) {
    private val image = makeCover(host.app, widthDp, heightDp).apply { layoutParams = params() }
    private val placeholder = makeCoverPlaceholder(host.app, widthDp, heightDp).apply { layoutParams = params() }
    val check: ImageView = makeSelectionCheck(host.app)

    fun addArtTo(frame: FrameLayout) {
        frame.addView(image)
        frame.addView(placeholder)
    }

    fun bind(
        story: Story,
        selected: Boolean?,
    ) {
        // Coil sizes a load from the view, and a reused view still has its previous slot's size; grid columns
        // differ by a pixel, so the cover would decode for the wrong width. Without bounds, Coil waits for this
        // slot's layout, as it did for the fresh view each bind used to create.
        image.layout(0, 0, 0, 0)
        val hasCover = host.loadStoryCover(story, image)
        image.visibility = if (hasCover) View.VISIBLE else View.GONE
        placeholder.visibility = if (hasCover) View.GONE else View.VISIBLE
        bindSelectionCheck(check, selected)
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

/** The round check on a cover's top-start corner in selection mode; [bindSelectionCheck] styles it. */
private fun makeSelectionCheck(context: Context): ImageView {
    val size = context.dp(CHECK_DP)
    return ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        layoutParams =
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(context.dp(Spacing.XS + 2), context.dp(Spacing.XS + 2), 0, 0)
            }
    }
}

/** Hidden while browsing; in selection mode hollow, or gold with a check when [selected]. */
private fun bindSelectionCheck(
    check: ImageView,
    selected: Boolean?,
) {
    if (selected == null) {
        check.visibility = View.GONE
        return
    }
    val colors = ThemeManager.colors
    val context = check.context
    val size = context.dp(CHECK_DP)
    check.visibility = View.VISIBLE
    if (selected) {
        check.background = roundedBg(colors.primary, size / 2f)
        check.setImageDrawable(context.tintedIcon(R.drawable.wna_check, colors.onPrimary))
        check.contentDescription = "Selected"
    } else {
        check.background =
            strokeBg(
                ColorUtils.setAlphaComponent(Color.BLACK, IDLE_CHECK_ALPHA),
                size / 2f,
                Color.WHITE,
                context.dp(2),
            )
        check.setImageDrawable(null)
        check.contentDescription = "Not selected"
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
