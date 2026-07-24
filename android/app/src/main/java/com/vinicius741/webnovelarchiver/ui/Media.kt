package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.layout.ChapterCoveragePlanning

// ------------------------------------------------------------------
// Cover image + placeholder
// ------------------------------------------------------------------

fun makeCover(
    context: Context,
    widthDp: Int,
    heightDp: Int,
): ImageView {
    val t = ThemeManager.current
    return ImageView(context).apply {
        contentDescription = "Cover"
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(t.colors.surfaceVariant)
        layoutParams =
            LinearLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp)).apply {
                setMargins(0, 0, context.dp(Space.LG), 0)
            }
        roundCorners(t.shapes.cardRadius.toFloat() * 0.7f)
    }
}

fun makeCoverPlaceholder(
    context: Context,
    widthDp: Int,
    heightDp: Int,
): View {
    val t = ThemeManager.current
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(t.colors.surfaceVariant)
        layoutParams =
            LinearLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp)).apply {
                setMargins(0, 0, context.dp(Space.LG), 0)
            }
        roundCorners(t.shapes.cardRadius.toFloat() * 0.7f)
        addView(
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_book_open, t.colors.onSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(context.dp(Space.XL + 4), context.dp(Space.XL + 4))
            },
        )
    }
}

// ------------------------------------------------------------------
// Progress bar (thin, rounded, themed)
// ------------------------------------------------------------------

fun makeProgress(
    context: Context,
    progress: Float,
    visible: Boolean = true,
): View {
    val t = ThemeManager.current
    val bar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = (progress.coerceIn(0f, 1f) * 100).toInt()
            progressTintList = ColorStateList.valueOf(t.colors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(t.colors.surfaceVariant)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(4))
            visibility = if (visible) View.VISIBLE else View.GONE
        }
    return bar
}

// ------------------------------------------------------------------
// Badges + pills
// ------------------------------------------------------------------

fun makeBadge(
    context: Context,
    text: String,
    bgColor: Int,
    fgColor: Int,
): TextView {
    val radius = context.dp(Space.SM + 2).toFloat()
    return TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_SMALL.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fgColor)
        setPadding(context.dp(Space.SM), context.dp(3), context.dp(Space.SM), context.dp(3))
        includeFontPadding = false
        background = roundedBg(bgColor, radius)
    }
}

fun makeStatPill(
    context: Context,
    label: String,
    value: String,
): View {
    val t = ThemeManager.current
    val radius = context.dp(t.shapes.chipRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(Space.MD), context.dp(Space.SM), context.dp(Space.MD), context.dp(Space.SM))
        background = roundedBg(t.colors.surfaceVariant, radius)
        addView(
            makeText(context, value, Type.TITLE_MEDIUM, t.colors.onSurface).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        addView(
            makeText(context, label, Type.LABEL_SMALL, t.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 2, 0, 0)
            },
        )
    }
}

/** Small rounded count chip (e.g. "3 failed") tinted by `fg`. Drops to zero-width when value is 0. */
fun makeCountChip(
    context: Context,
    label: String,
    value: Int,
    fg: Int,
): View {
    val t = ThemeManager.current
    val radius = context.dp(t.shapes.chipRadius).toFloat()
    return TextView(context).apply {
        text = "$value $label"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fg)
        setPadding(context.dp(Space.SM + 2), context.dp(Space.XS + 1), context.dp(Space.SM + 2), context.dp(Space.XS + 1))
        includeFontPadding = false
        background = roundedBg(t.colors.surfaceVariant, radius)
        visibility = if (value > 0) View.VISIBLE else View.GONE
    }
}

/** Compact "3 / 9 chapters" progress summary: a fractional value on the left, a thin bar on the right. */
fun makeProgressSummary(
    context: Context,
    done: Int,
    total: Int,
): View {
    val t = ThemeManager.current
    val ratio = if (total > 0) done.toFloat() / total else 0f
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        // MATCH_PARENT so the weighted ProgressBar below stretches to fill the available width
        // (the row/column it's placed in). Without this the root defaults to WRAP_CONTENT and the
        // weight has no leftover space to claim, leaving the bar a thin sliver.
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(
            TextView(context).apply {
                text = "$done / $total"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(t.colors.onSurface)
                includeFontPadding = false
            },
        )
        addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (ratio.coerceIn(0f, 1f) * 100).toInt()
                progressTintList = ColorStateList.valueOf(t.colors.primary)
                progressBackgroundTintList = ColorStateList.valueOf(t.colors.surfaceVariant)
                layoutParams = LinearLayout.LayoutParams(0, context.dp(4), 1f).apply { marginStart = context.dp(Space.SM + 2) }
            },
        )
    }
}

/** Updates an existing [makeProgressSummary] without replacing its view hierarchy. */
fun updateProgressSummary(
    view: View,
    done: Int,
    total: Int,
) {
    val row = view as? LinearLayout ?: return
    (row.getChildAt(0) as? TextView)?.text = "$done / $total"
    (row.getChildAt(1) as? ProgressBar)?.progress =
        if (total > 0) ((done.toFloat() / total).coerceIn(0f, 1f) * 100).toInt() else 0
}

// ------------------------------------------------------------------
// Chapter coverage bar — fills only where chapters are actually downloaded
// ------------------------------------------------------------------
//
// The legacy [makeProgressSummary] only knows a count, so 7/100 always fills the
// first 7% from the left even when those chapters are the LAST seven. This widget
// takes the per-chapter `downloaded` flags and fills only the slots whose chapter is
// on disk — so a "last 7 of 100" download shows as fill at the RIGHT end. Contiguous
// downloads are drawn as ONE rounded segment (no per-chapter hairline seams), and the
// bookmarked chapter gets a pin marker raised above the bar so it can't blend into
// the fill.

/**
 * Horizontal capsule that shows which chapters of a novel are downloaded, plus a bookmark
 * marker. Each chapter occupies one equal-width slot; contiguous runs of downloaded chapters
 * are drawn as single rounded segments in the theme primary. [bookmarkFraction] (0..1, null
 * when there is no bookmark) raises a pin above the bar at the bookmarked chapter's centre:
 * a downward triangle with a stem through the capsule, filled in the theme error colour and
 * outlined in the surface colour so it separates from every theme's fill and track. Drawn
 * from pure primitives (a boolean array + a fraction) so this stays a dumb UI widget with no
 * domain coupling; slot geometry comes from [ChapterCoveragePlanning].
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

    /** Surface-coloured stroke drawn under the bookmark pin: an outline that guarantees the
     *  marker separates from whatever is behind it (fill, track, or card) in every theme. */
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

    /**
     * A plain [View] with `WRAP_CONTENT` height measures to 0 (no intrinsic height like
     * [ProgressBar] has), which collapses the bar to nothing. Enforce the total height here so
     * the bar stays visible regardless of how its layout params are configured at the call site.
     */
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
        // The marker must stand out against BOTH the filled segments and the track. In some themes
        // the tertiary colour lands too close to primary (e.g. Obsidian's gold primary vs gold
        // tertiary), so the bookmark vanishes into the fill. The error red is distinct from every
        // theme's primary (gold/blue/green/dark-blue) and reads as a clear marker everywhere.
        bookmarkPaint.color = c.error
        // The halo does the real separation work: a surface-coloured outline around the pin that
        // contrasts with the red, the primary fill, and the track in all four themes.
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
        // The capsule bar is pinned to the BOTTOM of the view; the strip above it is reserved
        // for the bookmark pin, so the marker never has to compete with the fill for the same
        // few pixels (which is what made the old in-bar tick blend in).
        val barHeight = BAR_HEIGHT_DP * density
        val barTop = height - barHeight
        val radius = barHeight / 2f
        trackRect.set(0f, barTop, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        val total = downloaded.size
        if (total <= 0) return

        val slotWidth = width.toFloat() / total
        // ONE rounded rect per contiguous run. Drawing per-chapter slots instead leaves a
        // track-coloured hairline at every chapter boundary: adjacent anti-aliased edges share
        // pixels at fractional x positions and never composite to full opacity.
        for (range in ChapterCoveragePlanning.downloadedRuns(downloaded)) {
            runRect.set(range.first * slotWidth, barTop, (range.last + 1) * slotWidth, height.toFloat())
            // All four corners rounded: runs are separated by at least one empty slot, so the
            // capsule shape never collides with a neighbour, and runs touching the bar's ends
            // match the track's rounded caps.
            canvas.drawRoundRect(runRect, radius, radius, fillPaint)
        }

        drawBookmarkPin(canvas, total, slotWidth, barTop)
    }

    /**
     * Draws the bookmark as a pin raised above the bar: a downward triangle whose apex dips into
     * the capsule, with a slim stem through the capsule's full height. A surface-coloured halo is
     * stroked under the red fill first, so the marker is outlined against whatever is behind it —
     * this is what keeps it prominent where the old flat tick blended into the fill.
     */
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
        // Triangle: base across the top of the marker strip, apex dipping 1dp into the capsule.
        markerPath.moveTo(cx - triangleHalf, inset)
        markerPath.lineTo(cx + triangleHalf, inset)
        markerPath.lineTo(cx, barTop + density)
        markerPath.close()
        // Stem through the capsule so the exact bookmarked slot stays unambiguous. Ends `inset`
        // above the bottom edge so the halo's round cap isn't clipped by the view bounds.
        markerPath.addRect(cx - stemHalf, barTop, cx + stemHalf, height - inset, Path.Direction.CW)
        canvas.drawPath(markerPath, bookmarkHaloPaint)
        canvas.drawPath(markerPath, bookmarkPaint)
    }
}

/** Compact "X / Y chapters" summary whose bar fills only the chapters actually on disk and
 *  marks the bookmarked chapter with a pin raised above the bar. Mirrors the layout of
 *  [makeProgressSummary] (count text on the left, weighted bar on the right) so it slots into
 *  the same call sites. */
fun makeChapterCoverageSummary(
    context: Context,
    downloaded: BooleanArray,
    bookmarkFraction: Float?,
    done: Int,
    total: Int,
): View {
    val t = ThemeManager.current
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(
            TextView(context).apply {
                text = "$done / $total"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(t.colors.onSurface)
                includeFontPadding = false
            },
        )
        addView(
            ChapterCoverageBar(context, downloaded, bookmarkFraction).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, context.dp(COVERAGE_BAR_HEIGHT_DP), 1f).apply {
                        marginStart = context.dp(Space.SM + 2)
                    }
            },
        )
    }
}

/** Patches an existing [makeChapterCoverageSummary] in place (count text + bar) without
 *  rebuilding the view hierarchy — used for live download ticks. */
fun updateChapterCoverageSummary(
    view: View,
    downloaded: BooleanArray,
    bookmarkFraction: Float?,
    done: Int,
    total: Int,
) {
    val row = view as? LinearLayout ?: return
    (row.getChildAt(0) as? TextView)?.text = "$done / $total"
    (row.getChildAt(1) as? ChapterCoverageBar)?.bind(downloaded, bookmarkFraction)
}

/** Capsule height (dp) for the [ChapterCoverageBar]. */
private const val BAR_HEIGHT_DP = 6

/** Height (dp) of the strip above the capsule reserved for the bookmark pin's triangle. */
private const val BOOKMARK_MARKER_HEIGHT_DP = 5

/** Total [ChapterCoverageBar] height (dp): marker strip + capsule. Fixed regardless of whether a
 *  bookmark exists so binding/unbinding a bookmark never triggers a layout jump. */
private const val COVERAGE_BAR_HEIGHT_DP = BAR_HEIGHT_DP + BOOKMARK_MARKER_HEIGHT_DP

/** Half-width (dp) of the bookmark pin's triangle base. */
private const val BOOKMARK_TRIANGLE_HALF_WIDTH_DP = 3f

/** Half-width (dp) of the bookmark pin's stem through the capsule. */
private const val BOOKMARK_STEM_HALF_WIDTH_DP = 1.5f

/** Stroke width (dp) of the surface-coloured halo outlining the bookmark pin. */
private const val BOOKMARK_HALO_WIDTH_DP = 2.5f
