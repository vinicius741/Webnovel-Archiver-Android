package com.vinicius741.webnovelarchiver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import com.vinicius741.webnovelarchiver.R

// ------------------------------------------------------------------
// Inputs
// ------------------------------------------------------------------

private fun inputBackground(context: Context): StateListDrawable {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.searchRadius).toFloat()
    return StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            strokeBg(colors.surfaceVariant, radiusPx, colors.primary, context.dp(2)),
        )
        addState(
            intArrayOf(),
            strokeBg(colors.surfaceVariant, radiusPx, colors.outlineVariant, context.dp(2)),
        )
    }
}

internal fun EditText.applyInputStyle(
    hintText: String,
    inputType: Int,
    singleLine: Boolean = true,
) {
    val colors = ThemeManager.colors
    setHintTextColor(colors.onSurfaceVariant)
    setTextColor(colors.onSurface)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_LARGE.size())
    this.inputType = inputType
    setPadding(context.dp(Space.MD + 2), context.dp(Space.XS + 2), context.dp(Space.MD + 2), context.dp(Space.XS + 2))
    includeFontPadding = false
    background = inputBackground(context)
    this.hint = hintText
    minimumHeight = context.dp(48)
    if (singleLine) setSingleLine()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.setTint(colors.primary)
    if (singleLine) gravity = android.view.Gravity.CENTER_VERTICAL else gravity = android.view.Gravity.TOP
}

fun makeSearchField(
    context: Context,
    hint: String,
): EditText = EditText(context).apply { applyInputStyle(hint, InputType.TYPE_CLASS_TEXT) }

fun makeField(
    context: Context,
    value: String,
    hint: String,
    inputType: Int,
): EditText =
    EditText(context).apply {
        applyInputStyle(hint, inputType)
        setText(value)
    }

/**
 * Lets a multi-line field capped by `maxLines` scroll its own text inside a scrolling screen:
 * while the field has overflow, a drag on it moves the text instead of the page, and (Android 10+)
 * a visible scrollbar shows there is more.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun EditText.scrollTextInsideParent() {
    // A View built in code has no scrollbar drawable until one is set; reading the default thumb NPEs.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        verticalScrollbarThumbDrawable = roundedBg(ThemeManager.colors.outline, context.dp(2).toFloat())
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
    }
    setOnTouchListener { view, event ->
        val overflows = view.canScrollVertically(1) || view.canScrollVertically(-1)
        if (overflows && event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
}

/**
 * Tints a CheckBox so the box matches the active theme instead of falling back to the
 * platform default color. Mirrors the approach used by `ChapterSelectionAdapter`:
 * the checked fill uses `primary`, the unchecked stroke uses `outline`, and a dimmed
 * variant signals the disabled state.
 */
fun CheckBox.applyCheckBoxTint() {
    val colors = ThemeManager.colors
    // ~55% opacity applied to the unchecked color so a disabled box reads as muted rather
    // than the full-strength outline, matching how Material dims inactive controls.
    val disabledOutline = blend(colors.outline, colors.surface, 0.45f)
    buttonTintList =
        ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(disabledOutline, colors.primary, colors.outline),
        )
}

/** Linear RGB blend between [from] and [to] by [fraction] (0 = from, 1 = to). */
private fun blend(
    from: Int,
    to: Int,
    fraction: Float,
): Int {
    val f = fraction.coerceIn(0f, 1f)

    fun lerp(
        a: Float,
        b: Float,
    ): Int = (a + (b - a) * f).toInt()
    val a = lerp(Color.alpha(from).toFloat(), Color.alpha(to).toFloat())
    val r = lerp(Color.red(from).toFloat(), Color.red(to).toFloat())
    val g = lerp(Color.green(from).toFloat(), Color.green(to).toFloat())
    val b = lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat())
    return Color.argb(a, r, g, b)
}

fun styledCheckBox(checkBox: CheckBox) {
    val colors = ThemeManager.colors
    checkBox.setTextColor(colors.onSurface)
    checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_MEDIUM.size())
    checkBox.setPadding(checkBox.context.dp(Space.XS + 2), 0, 0, 0)
    checkBox.includeFontPadding = false
    checkBox.applyCheckBoxTint()
}
