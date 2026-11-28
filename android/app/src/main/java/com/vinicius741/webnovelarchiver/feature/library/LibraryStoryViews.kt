package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeChapterCoverageSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.updateChapterCoverageSummary
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

private data class LibraryProgressTag(
    val storyId: String,
)

/** What a Library card shows and does: its selection state (read when it binds) and where its taps go. */
internal class LibraryCardEvents(
    /** null while browsing; in selection mode, whether the story is selected. */
    val selectionOf: (storyId: String) -> Boolean?,
    val onOpen: (storyId: String) -> Unit,
    val onStartSelection: (storyId: String) -> Unit,
    val onToggle: (storyId: String) -> Unit,
    val onAddStory: () -> Unit,
)

/** A Library list card or cover tile: its views are built once per holder, and [bind] sets one story's values. */
internal interface LibraryCardView {
    val root: LinearLayout

    fun bind(
        story: Story,
        events: LibraryCardEvents,
    )
}

/** A v3 list card: cover, title, author, source/status/score, Patreon, tags, and chapter coverage. */
internal class LibraryStoryCard(
    host: ScreenHost,
) : LibraryCardView {
    private val content = LibraryStoryCardContent(host)
    override val root: LinearLayout =
        makeCard(host.app).apply {
            val pad = host.app.dp(Space.MD)
            setPadding(pad, pad, pad, pad)
            clipToOutline = true
            addView(content.row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            makeLibraryCardTappable(this)
        }

    override fun bind(
        story: Story,
        events: LibraryCardEvents,
    ) {
        val selected = events.selectionOf(story.id)
        content.bind(story, selected)
        wireLibraryStoryContent(root, story, selected, events)
        applyListCardSelection(root, selected)
        remeasureLibraryCard(root)
    }
}

/**
 * Makes the next layout measure every view of a rebound card afresh. A text change does not always reach the
 * holder as a layout request, and a holder that is not re-measured keeps the previous story's line heights (a
 * one-line title under a two-line gap). Building fresh views, as cards did before, never had stale sizes.
 */
internal fun remeasureLibraryCard(view: View) {
    view.forceLayout()
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) remeasureLibraryCard(view.getChildAt(index))
    }
}

/** The ripple and click flags a card or tile keeps for its whole life. */
internal fun makeLibraryCardTappable(card: View) {
    card.foreground = selectableRipple(ThemeManager.colors.onSurface)
    card.isClickable = true
    card.isFocusable = true
}

/** Tap (open/toggle) and long-press (select/toggle) on one story's card or tile. */
internal fun wireLibraryStoryContent(
    card: View,
    story: Story,
    selected: Boolean?,
    events: LibraryCardEvents,
) {
    if (selected == null) {
        card.setOnClickListener { events.onOpen(story.id) }
        card.setOnLongClickListener {
            events.onStartSelection(story.id)
            true
        }
    } else {
        // The model's new selection rebinds this story's cards on every page, not just this one.
        card.setOnClickListener { events.onToggle(story.id) }
        card.setOnLongClickListener {
            events.onToggle(story.id)
            true
        }
    }
}

/** Binds a tile's bare coverage capsule, tagged so [patchLibraryProgress] can update it during downloads. */
internal fun bindLibraryCoverageBar(
    bar: ChapterCoverageBar,
    story: Story,
) {
    if (story.totalChapters <= 0) {
        bar.visibility = View.GONE
        bar.tag = null
        return
    }
    bar.visibility = View.VISIBLE
    bar.tag = LibraryProgressTag(story.id)
    bar.bind(StoryBookmarkPlanning.downloadedFlags(story), StoryBookmarkPlanning.bookmarkFraction(story))
}

/** Patches only the coverage views for a story, preserving every scroll/gesture view. */
internal fun patchLibraryProgress(
    root: View,
    story: Story,
) {
    if (root.tag == LibraryProgressTag(story.id)) {
        val flags = StoryBookmarkPlanning.downloadedFlags(story)
        val bookmark = StoryBookmarkPlanning.bookmarkFraction(story)
        if (root is ChapterCoverageBar) {
            root.bind(flags, bookmark)
        } else {
            updateChapterCoverageSummary(root, flags, bookmark, story.downloadedChapters, story.totalChapters)
        }
    }
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) patchLibraryProgress(root.getChildAt(index), story)
    }
}

/** List card content for the author page's read-only cards, built and bound once. */
internal fun ScreenHost.buildStoryCard(
    story: Story,
    selected: Boolean?,
): LinearLayout = LibraryStoryCardContent(this).also { it.bind(story, selected) }.row

/**
 * List card content: a 78dp cover beside the text column, whose coverage row sits at the bottom. Optional
 * lines stay in the tree and are hidden (GONE) when a story has nothing to show there.
 */
internal class LibraryStoryCardContent(
    host: ScreenHost,
) {
    private val app = host.app
    val row: LinearLayout = CoverTextRow(app)
    private val cover =
        LibraryCoverSlot(host, LIST_COVER_WIDTH_DP, LIST_COVER_HEIGHT_DP) {
            FrameLayout.LayoutParams(app.dp(LIST_COVER_WIDTH_DP), app.dp(LIST_COVER_HEIGHT_DP))
        }
    private val title =
        makeText(app, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
            textSize = TITLE_SP
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    private val author =
        makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, app.dp(2), 0, 0)
        }
    private val badges = LibraryBadgeRow(host)
    private val patreon =
        makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.secondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, app.dp(Spacing.XS + 1), 0, 0)
        }
    private val tags =
        makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.faint).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, app.dp(Spacing.XS), 0, 0)
        }
    private val progress =
        makeChapterCoverageSummary(app, BooleanArray(0), null, 0, 0).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.dp(Spacing.SM)
                }
        }

    init {
        val coverFrame = FrameLayout(app)
        cover.addArtTo(coverFrame)
        coverFrame.addView(cover.check)
        row.addView(coverFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(author)
                addView(badges.view)
                addView(patreon)
                addView(tags)
                // Pushes the coverage row to the card's bottom edge when the cover is taller than the text.
                addView(View(app), LinearLayout.LayoutParams(1, 0, 1f))
                addView(progress)
            }
        row.addView(
            body,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = app.dp(Spacing.MD + 2) },
        )
    }

    fun bind(
        story: Story,
        selected: Boolean?,
    ) {
        cover.bind(story, selected)
        title.text = story.title
        author.text = "by ${story.author.ifBlank { "Unknown" }}"
        badges.bind(story, System.currentTimeMillis())
        val earnings = story.patreonStats?.let(PatreonEarningsPlanning::estimate)
        patreon.visibility = if (earnings != null) View.VISIBLE else View.GONE
        if (earnings != null) patreon.text = formatLibraryPatreonStats(earnings)
        val shownTags = story.tags?.takeIf { it.isNotEmpty() }
        tags.visibility = if (shownTags != null) View.VISIBLE else View.GONE
        if (shownTags != null) tags.text = shownTags.take(LIBRARY_TAG_PREVIEW).joinToString(" • ")
        if (story.totalChapters > 0) {
            progress.visibility = View.VISIBLE
            progress.tag = LibraryProgressTag(story.id)
            progress.render(
                story.downloadedChapters,
                story.totalChapters,
                StoryBookmarkPlanning.downloadedFlags(story),
                StoryBookmarkPlanning.bookmarkFraction(story),
            )
        } else {
            progress.visibility = View.GONE
            progress.tag = null
        }
    }
}

/**
 * The list card's cover/text row. A wrap-height LinearLayout sizes itself only from the children that don't
 * fill its height, so with the text column at MATCH_PARENT the row stayed exactly as tall as the cover. That
 * clipped the chapter count and bar off any card whose title, Patreon line, and tags ran taller. This row
 * also counts each child's natural height, then lays them out at that height so the column's spacer still
 * pins the coverage row to the bottom.
 */
private class CoverTextRow(
    context: Context,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return
        var tallest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val params = child.layoutParams as MarginLayoutParams
            tallest = maxOf(tallest, child.measuredHeight + params.topMargin + params.bottomMargin)
        }
        val height = resolveSize(maxOf(measuredHeight, tallest + paddingTop + paddingBottom), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

private fun formatLibraryPatreonStats(earnings: PatreonEarningsPlanning.PatreonEarnings): String {
    val amount =
        earnings.monthlyUsdCents?.let { cents ->
            val prefix = if (earnings.amountIsEstimated) "~" else ""
            "${prefix}${'$'}${NumberFormat.getIntegerInstance(Locale.US).format((cents / 100.0).roundToLong())}/mo"
        }
    val membersPrefix = if (earnings.membersIsEstimated) "~" else ""
    val members = "${membersPrefix}${NumberFormat.getIntegerInstance().format(earnings.paidMembers)}"
    val membersLabel = if (earnings.membersIsEstimated) "est. paid" else "paid"
    return if (amount != null) "Patreon $amount · $members $membersLabel" else "Patreon · $members $membersLabel"
}

/** Selected list cards take a warm fill and a gold border. */
private fun applyListCardSelection(
    card: LinearLayout,
    selected: Boolean?,
) {
    val colors = ThemeManager.colors
    val context = card.context
    val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
    card.background =
        if (selected == true) {
            strokeBg(
                SELECTED_CARD_FILL,
                radius,
                ColorUtils.setAlphaComponent(colors.primary, SELECTED_BORDER_ALPHA),
                context.dp(1).coerceAtLeast(1),
            )
        } else {
            strokeBg(colors.elevation1, radius, colors.line, context.dp(1).coerceAtLeast(1))
        }
}

/** How many tags a list card previews. */
internal const val LIBRARY_TAG_PREVIEW = 4
private const val LIST_COVER_WIDTH_DP = 78
private const val LIST_COVER_HEIGHT_DP = LIST_COVER_WIDTH_DP * 3 / 2
private const val TITLE_SP = 15.5f
private const val SELECTED_CARD_FILL = 0xFF2B2414.toInt()
private const val SELECTED_BORDER_ALPHA = 0x80
