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
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the source-native facts as a flat chip flow on Details, reusing the same badge idiom as the
 * tags row instead of a boxed stat widget. Selected metrics come back as surface-variant chips
 * (e.g. "85.2K followers"), content warnings as error chips, and the source "updated at" date as a
 * single trailing tertiary chip so it no longer needs its own header line. Returns null when there is
 * nothing to show, so the row simply does not render.
 */
internal fun ScreenHost.buildSourceMetadataFlow(story: Story): View? {
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    val metrics =
        SourceMetadataPlanning.detailMetrics(
            sourceName = provider?.name,
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
        setPadding(0, dp(Space.MD), 0, dp(Space.XS))
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
                ),
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

private fun formatSourceMetric(metric: SourceMetric): String {
    val value = metric.value
    val suffix =
        when {
            value >= 1_000_000_000L -> "B"
            value >= 1_000_000L -> "M"
            value >= 1_000L -> "K"
            else -> ""
        }
    if (suffix.isBlank()) return NumberFormat.getIntegerInstance(Locale.US).format(value)
    val divisor =
        when (suffix) {
            "B" -> 1_000_000_000.0
            "M" -> 1_000_000.0
            else -> 1_000.0
        }
    val scaled = value / divisor
    val decimals =
        if (scaled >= 100) {
            0
        } else if (scaled >= 10) {
            1
        } else {
            2
        }
    val formatted = String.format(Locale.US, "%.${decimals}f", scaled).trimEnd('0').trimEnd('.')
    return "$formatted$suffix"
}

internal fun formatSourceDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))
