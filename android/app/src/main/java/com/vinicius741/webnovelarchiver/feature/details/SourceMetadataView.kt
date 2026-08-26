package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the source-native facts as a flat chip flow in the Details summary, reusing the same badge
 * idiom as the source/status row instead of a boxed stat widget. Selected metrics come back as
 * surface-variant chips (e.g. "85.2K followers"), content warnings as error chips, and the source
 * "updated at" date as a single trailing tertiary chip so it no longer needs its own header line.
 * Returns null when there is nothing to show, so the row simply does not render.
 */
internal fun ScreenHost.buildSourceMetadataFlow(story: Story): View? {
    val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
    val metrics =
        SourceMetadataPlanning.detailMetrics(
            preferredKinds = provider?.descriptor?.featuredMetrics.orEmpty(),
            metadata = story.sourceMetadata,
            hasScore = !story.score.isNullOrBlank(),
        )
    val warnings =
        story.sourceMetadata.contentWarnings
            .filter(String::isNotBlank)
            .distinct()
    val updatedAt = story.sourceMetadata.updatedAt
    if (metrics.isEmpty() && warnings.isEmpty() && updatedAt == null) return null

    val colors = ThemeManager.colors
    return WrapLayout(app).apply {
        horizontalSpacingDp = Space.SM
        verticalSpacingDp = Space.SM
        setPadding(0, dp(Space.MD), 0, dp(Space.LG))
        // Metric chips use the secondaryContainer tint — the same color the source-name badge uses
        // in the header — so source-native numbers read as a distinct group from the neutral tag
        // chips below the description, instead of blending into one block.
        metrics.forEach { metric ->
            addView(
                makeBadge(
                    app,
                    "${formatSourceMetric(metric)} ${metric.kind.label}",
                    colors.secondaryContainer,
                    colors.onSecondaryContainer,
                ).apply {
                    // Every metric shown here is a featured kind, i.e. one the Trends screen charts;
                    // tapping jumps to that metric's chart (same shortcut as the score row).
                    setOnClickListener { showTrends(story.id, metricFocusTag(metric.kind)) }
                    contentDescription = "${formatSourceMetric(metric)} ${metric.kind.label}. Tap to view trends."
                },
            )
        }
        warnings.forEach { warning ->
            addView(makeBadge(app, warning, colors.errorContainer, colors.onErrorContainer))
        }
        updatedAt?.let {
            addView(makeBadge(app, "Updated ${formatSourceDate(it)}", colors.tertiaryContainer, colors.onTertiaryContainer))
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun formatSourceMetric(metric: SourceMetric): String = formatCompactCount(metric.value)

internal fun formatSourceDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))
