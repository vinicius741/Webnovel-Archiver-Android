package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
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
import com.vinicius741.webnovelarchiver.domain.story.AuthorPlanning
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.feature.library.showAuthorNovels
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerAction
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.feature.story.showCoverDialog
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.elevate
import com.vinicius741.webnovelarchiver.ui.formatScore
import com.vinicius741.webnovelarchiver.ui.formatSourceCount
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The top of the story hero: the whole cover (tap for the full-screen viewer), the title, the
 * author link, the source / status / type pills, and the score, which opens Trends.
 */

internal fun ScreenHost.buildHeroTop(story: Story): View {
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
    val cover = coverImage(story, widthDp = HERO_COVER_WIDTH_DP, heightDp = HERO_COVER_HEIGHT_DP, tapToOpen = false)
    cover.elevate(HERO_COVER_ELEVATION_DP)
    cover.contentDescription = "Cover of ${story.title}. Tap to view it full screen."
    cover.isClickable = true
    cover.setOnClickListener { openStoryCover(story) }
    row.addView(cover)
    val info = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    info.addView(
        makeText(app, story.title, Type.TITLE_LARGE, ThemeManager.colors.onSurface).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, TITLE_LINE_SPACING)
        },
    )
    info.addView(buildAuthorLine(story))
    buildHeroPills(story)?.let { info.addView(it) }
    buildScoreButton(story)?.let { info.addView(it) }
    row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row
}

/** The cover full screen, with a shortcut to the AI tab's cover versions. */
internal fun ScreenHost.openStoryCover(story: Story) {
    showCoverDialog(
        story,
        actions = listOf(CoverViewerAction(COVER_VERSIONS_ACTION, "Cover versions", R.drawable.wna_auto_awesome)),
    ) { _, viewer ->
        viewer.close()
        switchStoryTab(story.id, StoryTab.AI)
    }
}

private fun ScreenHost.buildHeroPills(story: Story): View? {
    val colors = ThemeManager.colors
    val pills = mutableListOf<View>()
    SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.let { pills += buildSourceSiteBadge(story, it.name) }
    publicationStatusBadge(story)?.let { pills += it }
    sourceAvailabilityBadge(story)?.let { pills += it }
    story.sourceMetadata.contentRating?.takeIf { it.isNotBlank() }?.let {
        pills += makeBadge(app, "Rated $it", colors.surfaceVariant, colors.onSurfaceVariant)
    }
    story.sourceMetadata.sourceType?.takeIf { it.isNotBlank() }?.let {
        pills += makeBadge(app, it, colors.surfaceVariant, colors.onSurfaceVariant)
    }
    story.sourceMetadata.sourceListingState?.takeIf { it.isNotBlank() }?.let {
        pills += makeBadge(app, it, colors.surfaceVariant, colors.onSurfaceVariant)
    }
    if (pills.isEmpty()) return null
    return WrapLayout(app).apply {
        horizontalSpacingDp = 6
        verticalSpacingDp = 6
        pills.forEach(::addView)
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
    }
}

/** Score pill: star, score, a trend arrow once the history loads, and "Trends". */
private fun ScreenHost.buildScoreButton(story: Story): View? {
    val score = story.score?.takeIf { it.isNotBlank() } ?: return null
    val colors = ThemeManager.colors
    val ratingCount = SourceMetadataPlanning.metric(story.sourceMetadata, SourceMetricKind.RATINGS)?.value
    val trendArrow =
        ImageView(app).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(TREND_ARROW_SIZE_DP), dp(TREND_ARROW_SIZE_DP)).apply { marginStart = dp(2) }
        }
    val radius = dp(SCORE_HEIGHT_DP / 2).toFloat()
    val pill =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(SCORE_HEIGHT_DP)
            setPadding(dp(Spacing.SM), 0, dp(Spacing.SM), 0)
            background = ripple(roundedBg(colors.elevation2, radius), radius, colors.onSurface)
            isClickable = true
            isFocusable = true
            contentDescription =
                buildString {
                    append("Score ${formatScore(score)}")
                    ratingCount?.let { append(" from ${formatSourceCount(it)} ratings") }
                    append(". Tap to view trends.")
                }
            addView(
                ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_star, colors.primary)) },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(Spacing.XS) },
            )
            addView(
                makeText(app, formatScore(score), Type.TITLE_MEDIUM, colors.onSurface).apply {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
            )
            addView(trendArrow)
            addView(
                makeText(
                    app,
                    ratingCount?.let { "${formatSourceCount(it)} ratings" } ?: "Trends",
                    Type.LABEL_MEDIUM,
                    colors.onSurfaceVariant,
                ).apply { setPadding(dp(6), 0, 0, 0) },
            )
            addView(
                ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_chevron_right, colors.onSurfaceVariant)) },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
            setOnClickListener { switchStoryTab(story.id, StoryTab.TRENDS, AppRoute.Trends(story.id, FOCUS_SCORE)) }
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.SM + 2)
                }
        }
    observeScoreTrend(story.id, score, pill, trendArrow)
    return pill
}

/** Patches the trend arrow in place from metric history (not held in the repository's in-memory cache). */
private fun ScreenHost.observeScoreTrend(
    storyId: String,
    score: String,
    row: View,
    trendArrow: ImageView,
) {
    scope.launch {
        val direction = MetricSnapshotPlanning.direction(MetricSnapshotPlanning.scoreSeries(repository.getMetricHistory(storyId)))
        // Skip if the user moved to a different story; a detached pill is fine to patch.
        if (activeStory?.id != storyId) return@launch
        val (icon, color, word) =
            when (direction) {
                TrendDirection.UP -> Triple(R.drawable.wna_up, ThemeManager.colors.tertiary, "up")
                TrendDirection.DOWN -> Triple(R.drawable.wna_down, ThemeManager.colors.error, "down")
                else -> return@launch
            }
        trendArrow.setImageDrawable(app.tintedIcon(icon, color))
        trendArrow.visibility = View.VISIBLE
        row.contentDescription = "Score ${formatScore(score)}, trending $word. Tap to view trends."
    }
}

/** Author name, underlined in gold; a known author opens the other library novels by them. */
private fun ScreenHost.buildAuthorLine(story: Story): TextView {
    val known = AuthorPlanning.hasKnownAuthor(story)
    return makeText(
        app,
        story.author,
        Type.BODY_LARGE,
        if (known) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant,
    ).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, dp(Spacing.XS + 2), dp(Spacing.SM), dp(Spacing.XS))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (known) {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            background = selectableRipple(ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            contentDescription = "Author ${story.author}. Tap to see other novels by this author."
            setOnClickListener { showAuthorNovels(story.id) }
        }
    }
}

private fun ScreenHost.buildSourceSiteBadge(
    story: Story,
    sourceName: String,
): TextView {
    val colors = ThemeManager.colors
    val radius = app.dp(Spacing.SM + 2).toFloat()
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

private const val COVER_VERSIONS_ACTION = "cover_versions"
private const val HERO_COVER_WIDTH_DP = 116
private const val HERO_COVER_HEIGHT_DP = 174
private const val HERO_COVER_ELEVATION_DP = 10f
private const val TITLE_LINE_SPACING = 1.05f
private const val SCORE_HEIGHT_DP = 36
private const val TREND_ARROW_SIZE_DP = 18
