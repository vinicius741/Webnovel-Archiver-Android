package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.ui.layout.ChapterCoveragePlanning

/**
 * Capsule bar of downloaded chapters, one slot per chapter, contiguous runs as rounded segments,
 * with an optional bookmark pin raised above. [bookmarkFraction] is 0..1, null when there is no
 * bookmark. Takes raw primitives only, no domain coupling; geometry from [ChapterCoveragePlanning].
 */
class ChapterCoverageBar(
    context: Context,
    private var downloaded: BooleanArray = BooleanArray(0),
    private var bookmarkFraction: Float? = null,
) : View(context) {
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val bookmarkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    /** Surface-coloured stroke under the pin so it separates from fill, track, and card in every theme. */
    private val bookmarkHaloPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val trackRect = RectF()
    private val runRect = RectF()
    private val markerPath = Path()
    private val density = context.resources.displayMetrics.density

    init {
        // Fixed height (marker strip + capsule) so the bar never collapses; width comes from layout.
        layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (COVERAGE_BAR_HEIGHT_DP * density).toInt(),
            )
        bookmarkHaloPaint.strokeWidth = BOOKMARK_HALO_WIDTH_DP * density
        applyTheme()
        describe()
    }

    /** A plain [View] has no intrinsic height, so WRAP_CONTENT would collapse the bar; enforce the fixed height. */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val desired = (COVERAGE_BAR_HEIGHT_DP * density).toInt()
        val resolvedHeight =
            when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                MeasureSpec.AT_MOST -> desired.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
                else -> desired
            }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolvedHeight)
    }

    /** Re-binds the per-chapter flags and optional bookmark fraction, then redraws. */
    fun bind(
        downloaded: BooleanArray,
        bookmarkFraction: Float?,
    ) {
        this.downloaded = downloaded
        this.bookmarkFraction = bookmarkFraction
        describe()
        invalidate()
    }

    private fun applyTheme() {
        val c = ThemeManager.colors
        trackPaint.color = c.surfaceVariant
        fillPaint.color = c.primary
        // Error red, not tertiary: in some themes tertiary lands too close to primary and the pin
        // vanishes into the fill. Red is distinct in every theme.
        bookmarkPaint.color = c.error
        bookmarkHaloPaint.color = c.surface
    }

    /** Re-reads theme colours on theme change and on detach/reattach. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTheme()
        invalidate()
    }

    private fun describe() {
        val done = downloaded.count { it }
        val total = downloaded.size
        val bookmarkChapter = ChapterCoveragePlanning.bookmarkSlot(bookmarkFraction, total)?.plus(1)
        contentDescription =
            buildString {
                append("$done of $total chapters downloaded")
                if (bookmarkChapter != null) append(", bookmark at chapter $bookmarkChapter")
            }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        applyTheme()
        // The capsule is pinned to the bottom; the strip above is reserved for the bookmark pin.
        val barHeight = BAR_HEIGHT_DP * density
        val barTop = height - barHeight
        val radius = barHeight / 2f
        trackRect.set(0f, barTop, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        val total = downloaded.size
        if (total <= 0) return

        val slotWidth = width.toFloat() / total
        // One rounded rect per run: per-chapter rects leave a hairline at each boundary because
        // adjacent anti-aliased edges never composite to full opacity.
        for (range in ChapterCoveragePlanning.downloadedRuns(downloaded)) {
            runRect.set(range.first * slotWidth, barTop, (range.last + 1) * slotWidth, height.toFloat())
            // All four corners rounded: runs never touch a neighbour and match the track's rounded ends.
            canvas.drawRoundRect(runRect, radius, radius, fillPaint)
        }

        drawBookmarkPin(canvas, total, slotWidth, barTop)
    }

    private fun drawBookmarkPin(
        canvas: Canvas,
        total: Int,
        slotWidth: Float,
        barTop: Float,
    ) {
        val slot = ChapterCoveragePlanning.bookmarkSlot(bookmarkFraction, total) ?: return
        val triangleHalf = BOOKMARK_TRIANGLE_HALF_WIDTH_DP * density
        // Keep the whole pin (including its halo) on screen even for bookmarks in the end slots.
        val inset = bookmarkHaloPaint.strokeWidth / 2f + density * 0.5f
        val cx = ((slot + 0.5f) * slotWidth).coerceIn(triangleHalf + inset, width - triangleHalf - inset)
        val stemHalf = BOOKMARK_STEM_HALF_WIDTH_DP * density
        markerPath.reset()
        markerPath.moveTo(cx - triangleHalf, inset)
        markerPath.lineTo(cx + triangleHalf, inset)
        markerPath.lineTo(cx, barTop + density)
        markerPath.close()
        // The stem ends inset above the bottom so the halo's round cap is not clipped by the view bounds.
        markerPath.addRect(cx - stemHalf, barTop, cx + stemHalf, height - inset, Path.Direction.CW)
        canvas.drawPath(markerPath, bookmarkHaloPaint)
        canvas.drawPath(markerPath, bookmarkPaint)
    }
}

/** "X / Y chapters" summary that mirrors [makeProgressSummary]: count text left, weighted bar right. */
class ChapterCoverageSummary(
    context: Context,
    downloaded: BooleanArray,
    bookmarkFraction: Float?,
    done: Int,
    total: Int,
) : LinearLayout(context) {
    private val count =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.colors.onSurface)
            includeFontPadding = false
        }
    private val bar = ChapterCoverageBar(context, downloaded, bookmarkFraction)

    init {
        val t = ThemeManager.current
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        count.setTextColor(t.colors.onSurface)
        addView(count)
        addView(
            bar.apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, context.dp(COVERAGE_BAR_HEIGHT_DP), 1f).apply {
                        marginStart = context.dp(Space.SM + 2)
                    }
            },
        )
        render(done, total, downloaded, bookmarkFraction)
    }

    fun render(
        done: Int,
        total: Int,
        downloaded: BooleanArray,
        bookmarkFraction: Float?,
    ) {
        count.text = "$done / $total"
        bar.bind(downloaded, bookmarkFraction)
    }
}

fun makeChapterCoverageSummary(
    context: Context,
    downloaded: BooleanArray,
    bookmarkFraction: Float?,
    done: Int,
    total: Int,
): ChapterCoverageSummary = ChapterCoverageSummary(context, downloaded, bookmarkFraction, done, total)

/** Patches an existing summary in place, used for live download ticks. */
fun updateChapterCoverageSummary(
    view: View,
    downloaded: BooleanArray,
    bookmarkFraction: Float?,
    done: Int,
    total: Int,
) {
    (view as? ChapterCoverageSummary)?.render(done, total, downloaded, bookmarkFraction)
}

private const val BAR_HEIGHT_DP = 6

/** Height (dp) of the strip above the capsule reserved for the bookmark pin's triangle. */
private const val BOOKMARK_MARKER_HEIGHT_DP = 5

/** Total bar height, fixed with or without a bookmark so binding one never causes a layout jump. */
private const val COVERAGE_BAR_HEIGHT_DP = BAR_HEIGHT_DP + BOOKMARK_MARKER_HEIGHT_DP

private const val BOOKMARK_TRIANGLE_HALF_WIDTH_DP = 3f

private const val BOOKMARK_STEM_HALF_WIDTH_DP = 1.5f

private const val BOOKMARK_HALO_WIDTH_DP = 2.5f
