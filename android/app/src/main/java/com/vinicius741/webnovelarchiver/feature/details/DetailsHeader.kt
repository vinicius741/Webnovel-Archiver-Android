package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning.TrendDirection
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeChapterCoverageSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.scoreRow
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

@Suppress("CyclomaticComplexMethod") // Header badges and optional metrics are independent presentation branches.
internal fun ScreenHost.buildDetailsHeader(story: Story): DetailsHeader {
    val col =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Space.XS), 0, dp(Space.SM))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    val cover = coverImage(story, widthDp = 150, heightDp = 225, tapToOpen = true)
    (cover.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(Space.LG))
    col.addView(cover)
    col.addView(
        makeText(app, story.title, Type.HEADLINE, ThemeManager.colors.onSurface).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        },
    )
    col.addView(
        makeText(app, story.author, Type.TITLE_MEDIUM, ThemeManager.colors.secondary).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(2), 0, dp(Space.MD))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        },
    )
    val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
    val publicationStatusBadge = publicationStatusBadge(story)
    val sourceAvailabilityBadge = sourceAvailabilityBadge(story)
    if (provider != null || publicationStatusBadge != null || sourceAvailabilityBadge != null) {
        val badgeRow =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

        fun addBadgeWithGap(view: View) {
            if (badgeRow.childCount > 0) {
                badgeRow.addView(
                    View(app).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(Space.SM), 0)
                    },
                )
            }
            badgeRow.addView(view)
        }
        provider?.let { addBadgeWithGap(buildSourceSiteBadge(story, it.name)) }
        publicationStatusBadge?.let {
            addBadgeWithGap(it)
        }
        sourceAvailabilityBadge?.let {
            addBadgeWithGap(it)
        }
        story.sourceMetadata.contentRating?.takeIf { it.isNotBlank() }?.let {
            addBadgeWithGap(makeBadge(app, "Rated $it", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
        }
        story.sourceMetadata.sourceType?.takeIf { it.isNotBlank() }?.let {
            addBadgeWithGap(makeBadge(app, it, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
        }
        story.sourceMetadata.sourceListingState?.takeIf { it.isNotBlank() }?.let {
            addBadgeWithGap(makeBadge(app, it, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
        }
        col.addView(badgeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // Tap opens Trends on the score series. The trend arrow loads async (metric history is not in
    // the repository's in-memory cache) and stays hidden without a meaningful direction.
    story.score?.takeIf { it.isNotBlank() }?.let { score ->
        val ratingCount = SourceMetadataPlanning.metric(story.sourceMetadata, SourceMetricKind.RATINGS)?.value
        val trendArrow =
            ImageView(app).apply {
                visibility = View.GONE
                layoutParams =
                    LinearLayout.LayoutParams(dp(TREND_ARROW_SIZE_DP), dp(TREND_ARROW_SIZE_DP)).apply {
                        marginStart = dp(Space.XS)
                    }
            }
        val row =
            scoreRow(score, iconSizeDp = 24, ratingCount = ratingCount, trailing = trendArrow).apply {
                contentDescription =
                    buildString {
                        append("Score $score")
                        ratingCount?.let { append(" from $it ratings") }
                        append(". Tap to view trends.")
                    }
                isClickable = true
                isFocusable = true
                background = selectableRipple(ThemeManager.colors.onSurface)
                setOnClickListener { showTrends(story.id, FOCUS_SCORE) }
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.SM)
                    }
            }
        col.addView(row)
        observeScoreTrend(story.id, score, row, trendArrow)
    }
    val progressSummary =
        if (story.totalChapters > 0) {
            makeChapterCoverageSummary(
                app,
                StoryBookmarkPlanning.downloadedFlags(story),
                StoryBookmarkPlanning.bookmarkFraction(story),
                story.downloadedChapters,
                story.totalChapters,
            )
        } else {
            null
        }
    if (progressSummary != null) {
        col.addView(
            progressSummary.apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.XS)
                        bottomMargin = dp(Space.XS)
                    }
            },
        )
    }
    return DetailsHeader(col, progressSummary)
}

/** Patches the trend arrow in place from metric history (not held in the repository's in-memory cache). */
private fun ScreenHost.observeScoreTrend(
    storyId: String,
    score: String,
    row: View,
    trendArrow: ImageView,
) {
    scope.launch {
        val direction =
            MetricSnapshotPlanning.direction(
                MetricSnapshotPlanning.scoreSeries(repository.getMetricHistory(storyId)),
            )
        // Skip if the user navigated to a different story. A header merely scrolled off-screen is
        // fine to patch; it reattaches with correct state.
        if (activeStory?.id != storyId) return@launch
        when (direction) {
            TrendDirection.UP -> {
                trendArrow.setImageDrawable(app.tintedIcon(R.drawable.wna_up, ThemeManager.colors.tertiary))
                trendArrow.visibility = View.VISIBLE
                row.contentDescription = "Score $score, trending up. Tap to view trends."
            }
            TrendDirection.DOWN -> {
                trendArrow.setImageDrawable(app.tintedIcon(R.drawable.wna_down, ThemeManager.colors.error))
                trendArrow.visibility = View.VISIBLE
                row.contentDescription = "Score $score, trending down. Tap to view trends."
            }
            else -> Unit
        }
    }
}

/** Header view plus the stable progress-summary child patched by live download events. */
internal data class DetailsHeader(
    val view: LinearLayout,
    val progressSummary: View?,
)

private fun ScreenHost.buildSourceSiteBadge(
    story: Story,
    sourceName: String,
): TextView {
    val colors = ThemeManager.colors
    val radius = app.dp(Space.SM + 2).toFloat()
    return makeBadge(
        app,
        sourceName,
        colors.secondaryContainer,
        colors.onSecondaryContainer,
        endIcon = app.tintedIcon(R.drawable.wna_open_external, colors.onSecondaryContainer),
    ).apply {
        background = ripple(roundedBg(colors.secondaryContainer, radius), radius, colors.onSurface)
        isClickable = true
        isFocusable = true
        contentDescription = "Open this novel's $sourceName page in the browser"
        setOnClickListener {
            runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
                .onFailure { toast("No app available to open source") }
        }
    }
}

private const val TREND_ARROW_SIZE_DP = 20
