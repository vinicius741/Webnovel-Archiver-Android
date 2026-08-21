package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar

/** One settings action row whose leading icon can render an inline loading state. */
class SettingActionRow(
    context: Context,
    iconRes: Int,
    title: CharSequence,
    description: CharSequence? = null,
    private val onClick: () -> Unit,
) : LinearLayout(context) {
    private val icon: ImageView = makeSettingRowIcon(context, iconRes)
    private val spinner: ProgressBar =
        ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
            isIndeterminate = true
            setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
        }
    private val iconSlot =
        FrameLayout(context).apply {
            addView(icon)
            addView(spinner)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
        background = selectableRipple(ThemeManager.colors.surface)
        layoutParams =
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(Space.XS)
            }
        addView(iconSlot, LayoutParams(context.dp(24), context.dp(24)))
        addView(
            makeSettingRowTextColumn(context, title, description),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(Space.MD) },
        )
        render(isLoading = false)
    }

    /** Applies loading/enabled state without rebuilding the row or changing its layout. */
    fun render(
        isLoading: Boolean = false,
        enabled: Boolean = true,
    ) {
        val interactive = enabled && !isLoading
        icon.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        alpha = if (interactive) 1f else 0.4f
        isClickable = interactive
        isFocusable = interactive
        isEnabled = interactive
        setOnClickListener(if (interactive) View.OnClickListener { onClick() } else null)
    }
}

fun makeSettingRowWithLoading(
    context: Context,
    iconRes: Int,
    title: CharSequence,
    description: CharSequence? = null,
    onClick: () -> Unit,
    loading: Boolean = false,
): SettingActionRow =
    SettingActionRow(context, iconRes, title, description, onClick)
        .also { it.render(loading) }

internal fun makeSettingRowIcon(
    context: Context,
    iconRes: Int,
): ImageView =
    ImageView(context).apply {
        setImageDrawable(context.tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
    }

internal fun makeSettingRowTextColumn(
    context: Context,
    title: CharSequence,
    description: CharSequence?,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            android.widget.TextView(context).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.colors.onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            },
        )
        description?.let {
            addView(
                android.widget.TextView(context).apply {
                    text = it
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                    setTextColor(ThemeManager.colors.onSurfaceVariant)
                    setPadding(0, context.dp(2), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
            )
        }
    }
