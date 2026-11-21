package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeChapterCoverageSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
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

/** Binds a v3 list card: cover, title, author, source/status/score, Patreon, tags, and chapter coverage. */
internal fun ScreenHost.bindLibraryStoryCard(
    card: LinearLayout,
    story: Story,
    events: LibraryCardEvents,
) {
    card.removeAllViews()
    val selected = events.selectionOf(story.id)
    val content = buildStoryCard(story, selected)
    card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    wireLibraryStoryContent(card, story, selected, events)
    applyListCardSelection(card, selected)
}

/** Tap (open/toggle) and long-press (select/toggle) on one story's card or tile. */
internal fun wireLibraryStoryContent(
    card: View,
    story: Story,
    selected: Boolean?,
    events: LibraryCardEvents,
) {
    card.foreground = selectableRipple(ThemeManager.colors.onSurface)
    card.isClickable = true
    card.isFocusable = true
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

/** Chapter coverage ("X / Y" and the capsule) tagged so [patchLibraryProgress] can update it during downloads. */
internal fun ScreenHost.makeLibraryProgressSummary(
    story: Story,
    topMarginDp: Int,
): View =
    makeChapterCoverageSummary(
        app,
        StoryBookmarkPlanning.downloadedFlags(story),
        StoryBookmarkPlanning.bookmarkFraction(story),
        story.downloadedChapters,
        story.totalChapters,
    ).apply {
        tag = LibraryProgressTag(story.id)
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(topMarginDp)
            }
    }

/** The bare coverage capsule (no count) under a cover-grid tile's art. */
internal fun ScreenHost.makeLibraryCoverageBar(story: Story): View =
    ChapterCoverageBar(app, StoryBookmarkPlanning.downloadedFlags(story), StoryBookmarkPlanning.bookmarkFraction(story)).apply {
        tag = LibraryProgressTag(story.id)
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

/** List card content: a 78dp cover beside the text column, whose coverage row sits at the bottom. */
internal fun ScreenHost.buildStoryCard(
    story: Story,
    selected: Boolean?,
): LinearLayout {
    val colors = ThemeManager.colors
    val row = CoverTextRow(app)
    row.addView(storyCover(story, selected))
    val body =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, story.title, Type.TITLE_SMALL, colors.onSurface).apply {
                    textSize = TITLE_SP
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                makeText(app, "by ${story.author.ifBlank { "Unknown" }}", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                },
            )
            libraryBadgeRow(story)?.let(::addView)
            story.patreonStats?.let(PatreonEarningsPlanning::estimate)?.let { earnings ->
                addView(
                    makeText(app, formatLibraryPatreonStats(earnings), Type.BODY_SMALL, colors.secondary).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(Spacing.XS + 1), 0, 0)
                    },
                )
            }
            story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                addView(
                    makeText(app, tags.take(TAG_PREVIEW).joinToString(" • "), Type.BODY_SMALL, colors.faint).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(Spacing.XS), 0, 0)
                    },
                )
            }
            // Pushes the coverage row to the card's bottom edge when the cover is taller than the text.
            addView(View(app), LinearLayout.LayoutParams(1, 0, 1f))
            if (story.totalChapters > 0) addView(makeLibraryProgressSummary(story, topMarginDp = Spacing.SM))
        }
    row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(Spacing.MD + 2) })
    return row
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

private fun ScreenHost.storyCover(
    story: Story,
    selected: Boolean?,
): View {
    val cover = coverImage(story, widthDp = LIST_COVER_WIDTH_DP, heightDp = LIST_COVER_WIDTH_DP * 3 / 2, tapToOpen = false)
    cover.layoutParams = FrameLayout.LayoutParams(dp(LIST_COVER_WIDTH_DP), dp(LIST_COVER_WIDTH_DP * 3 / 2))
    return FrameLayout(app).apply {
        addView(cover)
        if (selected != null) addView(selectionCheck(selected))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** Selected list cards take a warm fill and a gold border. */
private fun ScreenHost.applyListCardSelection(
    card: LinearLayout,
    selected: Boolean?,
) {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    card.background =
        if (selected == true) {
            strokeBg(
                SELECTED_CARD_FILL,
                radius,
                ColorUtils.setAlphaComponent(colors.primary, SELECTED_BORDER_ALPHA),
                dp(1).coerceAtLeast(1),
            )
        } else {
            strokeBg(colors.elevation1, radius, colors.line, dp(1).coerceAtLeast(1))
        }
}

private const val LIST_COVER_WIDTH_DP = 78
private const val TITLE_SP = 15.5f
private const val TAG_PREVIEW = 4
private const val SELECTED_CARD_FILL = 0xFF2B2414.toInt()
private const val SELECTED_BORDER_ALPHA = 0x80
