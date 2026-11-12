package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.elevate
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeCoverPlaceholder
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/*
 * Side-by-side cover comparison: the cover in use beside a candidate, each at about half the
 * card's width and always whole (letterboxed over a blur, never cropped). Tapping either opens the
 * full-screen viewer at that cover.
 */

/**
 * "Current | [candidateLabel]" pair. [candidate] is a Bitmap, File, or URL; [onOpen] receives 0
 * for the current cover and 1 for the candidate.
 */
internal fun ScreenHost.addAiCoverCompareRow(
    container: LinearLayout,
    story: Story,
    candidate: Any?,
    candidateLabel: String = "New",
    onOpen: (Int) -> Unit,
) {
    if (candidate == null) return
    val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(
        compareColumn(activeCoverSource(story), "Current", highlighted = false) {
            onOpen(0)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    row.addView(
        compareColumn(candidate, candidateLabel, highlighted = true) { onOpen(1) },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Spacing.MD) },
    )
    container.addView(
        row,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.MD)
            bottomMargin = dp(Spacing.SM)
        },
    )
}

/** One labeled half: the whole cover at 2:3 (capped on wide cards), its label under it. */
private fun ScreenHost.compareColumn(
    source: Any?,
    label: String,
    highlighted: Boolean,
    onOpen: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val radius = dp(COMPARE_RADIUS_DP).toFloat()
    val frame = AspectCoverFrame(app, dp(COMPARE_MAX_WIDTH_DP))
    val image: View =
        if (source == null) {
            makeCoverPlaceholder(app, 1, 1)
        } else {
            makeCover(app, 1, 1).also { loadImage(source, it) }
        }
    frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.roundCorners(COMPARE_RADIUS_DP.toFloat())
    frame.elevate(COMPARE_ELEVATION_DP)
    if (highlighted) {
        frame.foreground = strokeBg(Color.TRANSPARENT, radius, colors.primary, dp(2))
        frame.addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_open_in_full, colors.onSurface))
                scaleType = ImageView.ScaleType.CENTER
                background = roundedBg(ZOOM_HINT_BACKGROUND, dp(13).toFloat())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) },
        )
    }
    if (source != null) {
        frame.isClickable = true
        frame.isFocusable = true
        frame.contentDescription = "$label cover. Tap to view it full size."
        frame.setOnClickListener { onOpen() }
    }
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            makeText(app, label, Type.LABEL_LARGE, if (highlighted) colors.primary else colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            },
        )
    }
}

/** A 2:3 frame as wide as its slot allows, up to [maxWidthPx], centered in the slot. */
private class AspectCoverFrame(
    context: Context,
    private val maxWidthPx: Int,
) : FrameLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maxWidthPx)
        val height = (width * COVER_RATIO).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

private const val COVER_RATIO = 1.5f
private const val COMPARE_MAX_WIDTH_DP = 240
private const val COMPARE_RADIUS_DP = 12
private const val COMPARE_ELEVATION_DP = 8f
private const val ZOOM_HINT_BACKGROUND = 0xB40A0805.toInt()
