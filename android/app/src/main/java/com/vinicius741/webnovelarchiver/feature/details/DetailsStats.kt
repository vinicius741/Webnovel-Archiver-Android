package com.vinicius741.webnovelarchiver.feature.details

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// Overview stat cards (engagement numbers and the chapter count) and the content-warning pills.

/** Engagement numbers the source shares, plus the chapter count; each opens its Trends chart. */
internal fun ScreenHost.addStatCards(
    container: LinearLayout,
    story: Story,
    twoPane: Boolean,
) {
    val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
    val metrics =
        SourceMetadataPlanning.detailMetrics(
            preferredKinds = provider?.descriptor?.featuredMetrics.orEmpty(),
            metadata = story.sourceMetadata,
            hasScore = !story.score.isNullOrBlank(),
        )
    val cards =
        metrics.map { metric ->
            statCard(metricIcon(metric.kind), formatCompactCount(metric.value), metric.kind.label) { openMetricTrends(story.id, metric) }
        }
    val all =
        cards +
            statCard(
                R.drawable.wna_book_open,
                "%,d".format(story.chapters.size),
                "Chapters",
            ) { switchStoryTab(story.id, StoryTab.CHAPTERS) }
    val columns = if (twoPane) all.size.coerceAtMost(MAX_STAT_COLUMNS_WIDE) else statColumns(all.size)
    container.addView(
        GridLayout(app).apply {
            columnCount = columns
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
            all.forEach(::addView)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.LG)
        },
    )
}

private fun statColumns(count: Int): Int =
    when {
        count <= MAX_STAT_COLUMNS -> count
        count == 4 -> 2
        else -> MAX_STAT_COLUMNS
    }

private fun ScreenHost.openMetricTrends(
    storyId: String,
    metric: SourceMetric,
) = switchStoryTab(storyId, StoryTab.TRENDS, AppRoute.Trends(storyId, metricFocusTag(metric.kind)))

private fun metricIcon(kind: SourceMetricKind): Int =
    when (kind) {
        SourceMetricKind.FOLLOWERS, SourceMetricKind.READERS, SourceMetricKind.WATCHERS, SourceMetricKind.FOLLOWS -> R.drawable.wna_group
        SourceMetricKind.FAVORITES, SourceMetricKind.LIKES, SourceMetricKind.KUDOS, SourceMetricKind.BOOKMARKS -> R.drawable.wna_star
        SourceMetricKind.WORDS, SourceMetricKind.AVERAGE_WORDS, SourceMetricKind.PAGES -> R.drawable.wna_notes
        else -> R.drawable.wna_chart
    }

private fun ScreenHost.statCard(
    icon: Int,
    value: String,
    label: String,
    onClick: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val radius = dp(STAT_RADIUS_DP).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(Spacing.MD), dp(Spacing.SM + 2), dp(Spacing.MD), dp(Spacing.SM + 2))
        background = ripple(strokeBg(colors.elevation1, radius, colors.line, dp(1)), radius, colors.onSurface)
        isClickable = true
        isFocusable = true
        contentDescription = "$value $label"
        addView(ImageView(app).apply { setImageDrawable(app.tintedIcon(icon, colors.primary)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(
            makeText(app, value, Type.TITLE_MEDIUM, colors.onSurface).apply {
                textSize = STAT_VALUE_SP
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(Spacing.XS), 0, 0)
            },
        )
        addView(
            makeText(app, label, Type.LABEL_SMALL, colors.onSurfaceVariant).apply {
                maxLines = 1
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        setOnClickListener { onClick() }
    }
}

/** Content warnings as red pills, then when the source last updated the novel. */
internal fun ScreenHost.addWarningsAndUpdated(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val warnings =
        story.sourceMetadata.contentWarnings
            .filter(String::isNotBlank)
            .distinct()
    val updatedAt = story.sourceMetadata.updatedAt
    if (warnings.isEmpty() && updatedAt == null) return
    container.addView(
        WrapLayout(app).apply {
            horizontalSpacingDp = 6
            verticalSpacingDp = 6
            centerRows = true
            warnings.forEach { warning ->
                addView(
                    makeBadge(
                        app,
                        warning,
                        colors.errorContainer,
                        colors.onErrorContainer,
                    ).withStartIcon(R.drawable.wna_warning, colors.onErrorContainer),
                )
            }
            updatedAt?.let { addView(makeBadge(app, "Updated ${formatSourceDate(it)}", colors.elevation3, colors.onSurfaceVariant)) }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.MD)
        },
    )
}

internal fun android.widget.TextView.withStartIcon(
    icon: Int,
    color: Int,
): android.widget.TextView =
    apply {
        val size = context.dp(12)
        val drawable = context.tintedIcon(icon, color)?.apply { setBounds(0, 0, size, size) }
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = context.dp(3)
    }

private const val MAX_STAT_COLUMNS = 3
private const val MAX_STAT_COLUMNS_WIDE = 4
private const val STAT_RADIUS_DP = 14
private const val STAT_VALUE_SP = 17f
