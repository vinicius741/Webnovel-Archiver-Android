package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.children
import com.vinicius741.webnovelarchiver.ai.OpenRouterModel
import com.vinicius741.webnovelarchiver.ai.modelsOrNull
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg

/*
 * Reasoning control for the model picker. It lives inside the selected model's own row (label +
 * one connected segmented track on a single line) rather than in a separate panel, so tuning a
 * model reads as part of choosing it and costs one compact line instead of a block of the dialog.
 */

// Equal-width segments stay legible up to this many levels; wider sets (manual ids allow all
// seven) scroll horizontally inside the same track instead of shrinking below a tappable size.
private const val MAX_EQUAL_EFFORT_SEGMENTS = 5

private const val EFFORT_TRACK_HEIGHT_DP = 36

private const val EFFORT_TRACK_INSET_DP = 2

/**
 * One-line "Reasoning" row: a small label followed by a connected single-choice track with the
 * chosen level as a filled pill. [efforts] already includes the model-default entry.
 */
internal fun makeReasoningRow(
    context: Context,
    efforts: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            makeText(context, "Reasoning", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, 0, context.dp(Space.SM), 0)
            },
        )
        addView(makeEffortTrack(context, efforts, selected, onSelect))
    }

/** Connected single-choice control: one outlined track with the chosen level as a filled pill. */
private fun makeEffortTrack(
    context: Context,
    efforts: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
): View {
    val colors = ThemeManager.colors
    val inset = context.dp(EFFORT_TRACK_INSET_DP)
    val trackHeight = context.dp(EFFORT_TRACK_HEIGHT_DP)
    val segmentHeight = trackHeight - inset * 2
    val radiusPx = minOf(context.dp(ThemeManager.shapes.buttonRadius), trackHeight / 2).toFloat()
    val equalWidth = efforts.size <= MAX_EQUAL_EFFORT_SEGMENTS
    val track =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(inset, inset, inset, inset)
            background = strokeBg(colors.surface, radiusPx, colors.outlineVariant, context.dp(1))
        }
    efforts.forEach { effort ->
        val isSelected = effort == selected
        track.addView(
            makeText(
                context,
                AiReasoningEffort.shortLabel(effort),
                Type.LABEL_MEDIUM,
                if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(context.dp(Space.XS), 0, context.dp(Space.XS), 0)
                minimumWidth = context.dp(44)
                background =
                    ripple(
                        roundedBg(if (isSelected) colors.secondaryContainer else Color.TRANSPARENT, radiusPx),
                        radiusPx,
                        colors.onSurface,
                    )
                isClickable = true
                isFocusable = true
                this.isSelected = isSelected
                setOnClickListener { onSelect(effort) }
            },
            if (equalWidth) {
                LinearLayout.LayoutParams(0, segmentHeight, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, segmentHeight)
            },
        )
    }
    if (equalWidth) {
        track.layoutParams = LinearLayout.LayoutParams(0, trackHeight, 1f)
        return track
    }
    track.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, trackHeight)
    return HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(track)
        layoutParams = LinearLayout.LayoutParams(0, trackHeight, 1f)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            track.children.firstOrNull { it.isSelected }?.let { segment ->
                scrollTo((segment.left - (width - segment.width) / 2).coerceAtLeast(0), 0)
            }
        }
    }
}

/** Shared successful catalog; failures remain retryable on the next picker opening. */
internal suspend fun ScreenHost.loadAiModelCatalog(): List<OpenRouterModel>? =
    modelCatalogCache ?: app.appContainer.openRouter
        .modelsOrNull()
        ?.also { modelCatalogCache = it }
