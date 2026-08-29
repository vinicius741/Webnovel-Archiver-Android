package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.data.repository.coverFile
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
import java.io.File

/*
 * Side-by-side cover comparison for the AI Controls screen: the draft preview shows current vs
 * new, and the applied-state card shows the source and generated covers beside each other so the
 * show-AI preference reads as a choice between two visible images.
 */

private const val COMPARE_THUMB_WIDTH_DP = 110
private const val COMPARE_THUMB_HEIGHT_DP = 165

/**
 * A labeled cover thumbnail column. [source] is a URL string, [File], or [Bitmap]; null renders
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
 * "Current | New" pair for a pending cover draft: the cover the app shows right now (per the
 * show-AI preference) beside the generated candidate, so Apply is a visible before/after choice.
 */
internal fun ScreenHost.addAiCoverDraftCompareRow(
    container: LinearLayout,
    story: Story,
    draftBitmap: Bitmap?,
) {
    if (draftBitmap == null) return
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
    row.addView(compareCoverThumb(app, activeCoverSource(story), "Current"))
    row.addView(
        compareCoverThumb(app, draftBitmap, "New"),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = app.dp(Space.LG)
        },
    )
    container.addView(
        row,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

/**
 * "Source | AI" pair for the applied state. Shows whichever sides exist; with only one cover on
 * file it degrades to a single labeled thumbnail.
 */
internal fun ScreenHost.addAppliedCoverCompareRow(
    container: LinearLayout,
    story: Story,
) {
    val aiFile = repository.coverFile(story)
    val sourceUrl = story.coverUrl?.takeIf { it.isNotBlank() }
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
    if (sourceUrl != null) {
        row.addView(compareCoverThumb(app, sourceUrl, "Source"))
    }
    if (aiFile != null) {
        row.addView(
            compareCoverThumb(app, aiFile, "AI"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (sourceUrl != null) app.dp(Space.LG) else 0
            },
        )
    }
    container.addView(
        row,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}
