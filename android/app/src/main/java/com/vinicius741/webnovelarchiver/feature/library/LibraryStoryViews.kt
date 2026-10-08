package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.library.LibraryQuery
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeChapterCoverageSummary
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.scoreRow
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.updateChapterCoverageSummary
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

private data class LibraryProgressTag(
    val storyId: String,
)

internal fun ScreenHost.bindLibraryStoryCard(
    card: LinearLayout,
    story: Story,
    alignToTop: Boolean,
) {
    card.removeAllViews()
    // null = normal browsing; non-null = multi-select mode with this card's checked state.
    val selected = if (libraryScreenState.selectedStoryIds.isEmpty()) null else story.id in libraryScreenState.selectedStoryIds
    val content = buildStoryCard(story, if (alignToTop) Gravity.TOP else Gravity.CENTER_VERTICAL, selected)
    content.background = selectableRipple(ThemeManager.colors.onSurface)
    content.isClickable = true
    applySelectionStyle(card, content, selected)
    if (selected == null) {
        content.setOnClickListener { showDetails(story.id) }
        content.setOnLongClickListener {
            startLibrarySelection(story.id)
            true
        }
    } else {
        // Toggling refreshes this story's cards on every page (see refreshStoryCards), not just this one.
        val toggle = { toggleLibrarySelection(story.id) }
        content.setOnClickListener { toggle() }
        content.setOnLongClickListener {
            toggle()
            true
        }
    }
    // Extra row height belongs to the content, keeping the progress summary at the card's bottom.
    card.addView(
        content,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    if (story.totalChapters > 0) {
        card.addView(
            makeChapterCoverageSummary(
                card.context,
                StoryBookmarkPlanning.downloadedFlags(story),
                StoryBookmarkPlanning.bookmarkFraction(story),
                story.downloadedChapters,
                story.totalChapters,
            ).apply {
                tag = LibraryProgressTag(story.id)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(Space.MD)
                        }
            },
        )
    }
}

/** Patches only the count text and coverage bar for a story, preserving every scroll/gesture view. */
internal fun patchLibraryProgress(
    root: android.view.View,
    story: Story,
) {
    if (root.tag == LibraryProgressTag(story.id)) {
        updateChapterCoverageSummary(
            root,
            StoryBookmarkPlanning.downloadedFlags(story),
            StoryBookmarkPlanning.bookmarkFraction(story),
            story.downloadedChapters,
            story.totalChapters,
        )
    }
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) patchLibraryProgress(root.getChildAt(index), story)
    }
}

/** Horizontal library card content: 80×120 cover + stacked text. */
private fun ScreenHost.buildStoryCard(
    story: Story,
    contentGravity: Int,
    selected: Boolean?,
): LinearLayout {
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = contentGravity
        }
    row.addView(storyCover(story, selected))
    row.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, story.title, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            addView(
                makeText(app, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, dp(Space.XS), 0, 0)
                },
            )
            val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
            val publicationStatusBadge = publicationStatusBadge(story)
            val sourceAvailabilityBadge = sourceAvailabilityBadge(story)
            if (provider != null || publicationStatusBadge != null || sourceAvailabilityBadge != null) {
                addView(
                    LinearLayout(app).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(Space.XS), 0, 0)
                        provider?.let {
                            addView(makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary))
                        }
                        publicationStatusBadge?.let {
                            val badgeLayoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                            if (provider != null) {
                                badgeLayoutParams.marginStart = dp(Space.SM)
                            }
                            addView(
                                it,
                                badgeLayoutParams,
                            )
                        }
                        sourceAvailabilityBadge?.let {
                            val badgeLayoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        if (provider != null || publicationStatusBadge != null) marginStart = dp(Space.SM)
                                    }
                            addView(it, badgeLayoutParams)
                        }
                    },
                )
            }
            story.score?.takeIf { it.isNotBlank() }?.let { score ->
                addView(scoreRow(score))
            }
            story.patreonStats?.let(PatreonEarningsPlanning::estimate)?.let { earnings ->
                addView(
                    makeText(app, formatLibraryPatreonStats(earnings), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(Space.XS), 0, 0)
                    },
                )
            }
            story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                addView(
                    makeText(app, tags.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, dp(Space.XS), 0, 0)
                    },
                )
            }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    if (story.isArchived == true) {
        row.addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
                layoutParams =
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginStart = dp(Space.SM)
                    }
            },
        )
    }
    return row
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
): android.view.View {
    val cover = coverImage(story, widthDp = 80, heightDp = 120, tapToOpen = false)
    return if (selected == null) cover else coverWithSelectionBadge(cover)
}

/** Wraps the cover so the check badge overlays its corner; the cover stays fully visible. */
private fun ScreenHost.coverWithSelectionBadge(cover: android.view.View): android.view.View {
    val coverParams = cover.layoutParams as? LinearLayout.LayoutParams
    val badgeSize = dp(26)
    val inset = dp(Space.SM)
    cover.layoutParams = FrameLayout.LayoutParams(coverParams?.width ?: 0, coverParams?.height ?: 0)
    return FrameLayout(app).apply {
        addView(cover)
        addView(
            ImageView(app).apply {
                tag = SELECTION_BADGE_TAG
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(Space.XS), dp(Space.XS), dp(Space.XS), dp(Space.XS))
            },
            FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.START).apply {
                setMargins(inset, inset, 0, 0)
            },
        )
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = coverParams?.marginEnd ?: 0
            }
    }
}

/** Restyles one card's highlight outline and cover badge for [selected]; null clears both. */
private fun ScreenHost.applySelectionStyle(
    card: LinearLayout,
    content: LinearLayout,
    selected: Boolean?,
) {
    val colors = ThemeManager.colors
    card.foreground =
        if (selected == true) {
            strokeBg(
                ColorUtils.setAlphaComponent(colors.primary, SELECTED_FILL_ALPHA),
                dp(ThemeManager.current.shapes.cardRadius).toFloat(),
                colors.primary,
                dp(2),
            )
        } else {
            null
        }
    val badge = content.findViewWithTag<ImageView>(SELECTION_BADGE_TAG) ?: return
    val radius = dp(13).toFloat()
    if (selected == true) {
        badge.background = roundedBg(colors.primary, radius)
        badge.setImageDrawable(app.tintedIcon(R.drawable.wna_check, colors.onPrimary))
        badge.contentDescription = "Selected"
    } else {
        badge.background = strokeBg(ColorUtils.setAlphaComponent(colors.surface, BADGE_IDLE_ALPHA), radius, colors.onSurface, dp(2))
        badge.setImageDrawable(null)
        badge.contentDescription = "Not selected"
    }
}

private const val SELECTION_BADGE_TAG = "wna_library_selection_badge"
private const val SELECTED_FILL_ALPHA = 0x26
private const val BADGE_IDLE_ALPHA = 0xB3
