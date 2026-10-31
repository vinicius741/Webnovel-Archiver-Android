package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.showCoverZoomDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeCoverPlaceholder
import com.vinicius741.webnovelarchiver.ui.makeText

/*
 * Side-by-side cover comparison for the AI Controls screen: the "New cover ready" card and a
 * saved cover's details both show the cover in use beside the candidate.
 */

private const val COMPARE_THUMB_WIDTH_DP = 110
private const val COMPARE_THUMB_HEIGHT_DP = 165

/**
 * A labeled cover thumbnail column. [source] is a URL string, File, or Bitmap; null renders
 * the placeholder (e.g. a story with no source cover). Tap opens the pinch-zoom viewer.
 */
private fun ScreenHost.compareCoverThumb(
    context: Context,
    source: Any?,
    label: String,
): LinearLayout {
    val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
    val image: View =
        if (source == null) {
            makeCoverPlaceholder(context, COMPARE_THUMB_WIDTH_DP, COMPARE_THUMB_HEIGHT_DP)
        } else {
            makeCover(context, COMPARE_THUMB_WIDTH_DP, COMPARE_THUMB_HEIGHT_DP).apply {
                loadImage(source, this)
            }
        }
    image.setOnClickListener { if (source != null) showCoverZoomDialog(source, label) }
    column.addView(image)
    column.addView(
        makeText(context, label, Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(Space.XS), 0, 0)
        },
    )
    return column
}

/**
 * "Current | [candidateLabel]" pair: the cover the app shows right now (per the show-AI
 * preference) beside a candidate (a [Bitmap], [File], or URL), so choosing it is a visible
 * before/after decision.
 */
internal fun ScreenHost.addAiCoverCompareRow(
    container: LinearLayout,
    story: Story,
    candidate: Any?,
    candidateLabel: String = "New",
) {
    if (candidate == null) return
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
    row.addView(compareCoverThumb(app, activeCoverSource(story), "Current"))
    row.addView(
        compareCoverThumb(app, candidate, candidateLabel),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = app.dp(Space.LG)
        },
    )
    container.addView(
        row,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}
