package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

// Settings rows that can show an inline indeterminate spinner in their leading-icon slot while a
// background action runs. Split out of [Surfaces] so the loading-state controller + its row builder
// stay cohesive and the card/empty-state helpers in [Surfaces] stay small.

/**
 * A settings row that can show an inline indeterminate spinner in its leading-icon slot while a
 * background action runs. While [loading] is true the spinner replaces the icon, the whole row is
 * dimmed (matching [disableButton]), and taps are ignored. Returns a controller to flip the state
 * from the caller's coroutine without rebuilding the row.
 */
fun makeSettingRowWithLoading(
    context: Context,
    iconRes: Int,
    title: CharSequence,
    description: CharSequence? = null,
    onClick: () -> Unit,
    loading: Boolean = false,
): Pair<LinearLayout, SettingRowLoadingController> {
    val t = ThemeManager.current
    // Icon slot: a FrameLayout so the icon and spinner overlay in the same 24dp box and we can
    // toggle between them without touching the row's child indices.
    val iconSlot =
        FrameLayout(context).apply {
            val icon =
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(iconRes, t.colors.onSurfaceVariant))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
                }
            val spinner =
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(t.colors.primary)
                    isIndeterminate = true
                    // ProgressBar has its own padding; inset it so the 24dp box matches the icon size.
                    setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                    layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
                    visibility = if (loading) View.VISIBLE else View.GONE
                }
            addView(icon)
            addView(spinner)
        }
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = !loading
            isFocusable = !loading
            setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
            background = selectableRipple(t.colors.surface)
            if (loading) alpha = 0.4f
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(Space.XS)
                }
            addView(iconSlot, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(
                makeSettingRowTextColumn(context, title, description),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(Space.MD)
                },
            )
        }
    val controller =
        SettingRowLoadingController(
            row = row,
            spinner = iconSlot.getChildAt(1) as ProgressBar,
            onClick = onClick,
        )
    if (!loading) row.setOnClickListener { controller.click() }
    return row to controller
}

internal fun makeSettingRowIcon(
    context: Context,
    iconRes: Int,
): ImageView =
    ImageView(context).apply {
        setImageDrawable(context.tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

internal fun makeSettingRowTextColumn(
    context: Context,
    title: CharSequence,
    description: CharSequence?,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            TextView(context).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.colors.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            },
        )
        description?.let {
            addView(
                TextView(context).apply {
                    text = it
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                    setTextColor(ThemeManager.colors.onSurfaceVariant)
                    setPadding(0, context.dp(2), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
            )
        }
    }

/**
 * Toggles the inline loading state of a [makeSettingRowWithLoading] row from the caller's
 * coroutine, without rebuilding the view. While loading the spinner replaces the icon, the row
 * dims to match [disableButton], and taps are dropped so the action can't be re-triggered.
 */
class SettingRowLoadingController(
    private val row: LinearLayout,
    private val spinner: ProgressBar,
    private val onClick: () -> Unit,
) {
    private var loading = spinner.visibility == View.VISIBLE

    init {
        if (!loading) row.setOnClickListener { click() }
    }

    fun click() {
        if (!loading) onClick()
    }

    /** Shows/hides the spinner and enables/disables the row. */
    fun setLoading(loading: Boolean) {
        if (loading == this.loading) return
        this.loading = loading
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        row.alpha = if (loading) 0.4f else 1f
        row.isClickable = !loading
        row.isFocusable = !loading
        if (loading) {
            row.setOnClickListener(null)
        } else {
            row.setOnClickListener { click() }
        }
    }
}
