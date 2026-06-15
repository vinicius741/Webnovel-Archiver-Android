package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText

/* ------------------------------------------------------------------ */
/* Inputs                                                             */
/* ------------------------------------------------------------------ */

private fun EditText.applyInputStyle(hintText: String, inputType: Int) {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.searchRadius).toFloat()
    setHintTextColor(colors.onSurfaceVariant)
    setTextColor(colors.onSurface)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_LARGE.size())
    this.inputType = inputType
    setPadding(context.dp(Space.MD + 2), context.dp(Space.XS + 2), context.dp(Space.MD + 2), context.dp(Space.XS + 2))
    includeFontPadding = false
    background = strokeBg(colors.surfaceVariant, radiusPx, colors.outline, context.dp(1))
    this.hint = hintText
    setSingleLine()
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
