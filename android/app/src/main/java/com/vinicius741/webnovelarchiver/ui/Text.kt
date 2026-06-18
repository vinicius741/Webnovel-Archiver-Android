package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

// ------------------------------------------------------------------
// Text
// ------------------------------------------------------------------

fun makeText(
    context: Context,
    text: CharSequence,
    type: Type,
    color: Int = ThemeManager.colors.onSurface,
): TextView =
    TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size())
        setTextColor(color)
        if (type.bold()) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

fun Context.text(
    text: CharSequence,
    type: Type,
    color: Int = ThemeManager.colors.onSurface,
) = makeText(this, text, type, color)
