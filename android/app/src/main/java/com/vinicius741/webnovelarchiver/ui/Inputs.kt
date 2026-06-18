package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText

/* ------------------------------------------------------------------ */
/* Inputs                                                             */
/* ------------------------------------------------------------------ */

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

fun makeSearchField(context: Context, hint: String): EditText =
    EditText(context).apply { applyInputStyle(hint, InputType.TYPE_CLASS_TEXT) }

fun makeField(context: Context, value: String, hint: String, inputType: Int): EditText =
    EditText(context).apply {
        applyInputStyle(hint, inputType)
        setText(value)
    }

fun styledCheckBox(checkBox: CheckBox) {
    val colors = ThemeManager.colors
    checkBox.setTextColor(colors.onSurface)
    checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_MEDIUM.size())
    checkBox.setPadding(checkBox.context.dp(Space.XS + 2), 0, 0, 0)
    checkBox.includeFontPadding = false
}
