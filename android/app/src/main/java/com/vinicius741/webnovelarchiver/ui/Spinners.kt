package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R

/** Builds a dropdown spinner without relying on Android's light-themed platform row layouts. */
fun makeThemedSpinner(
    context: Context,
    labels: List<String>,
): Spinner {
    val theme = ThemeManager.current
    val colors = theme.colors
    val radiusPx = context.dp(theme.shapes.searchRadius).toFloat()
    val horizontalPadding = context.dp(Spacing.LG)
    val verticalPadding = context.dp(Spacing.MD)

    val adapter =
        object : ArrayAdapter<String>(context, 0, labels) {
            private fun row(
                convertView: View?,
                parent: ViewGroup,
                position: Int,
                selectedValue: Boolean,
            ): TextView {
                val textView =
                    (convertView as? TextView) ?: TextView(parent.context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_LARGE.size())
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        minHeight = context.dp(48)
                    }
                textView.text = getItem(position)
                textView.setTextColor(colors.onSurface)
                textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                textView.background =
                    if (selectedValue) {
                        null
                    } else {
                        selectableRipple(colors.onSurface)
                    }
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null,
                    null,
                    if (selectedValue) context.tintedIcon(R.drawable.wna_chevron_down, colors.onSurfaceVariant) else null,
                    null,
                )
                if (selectedValue) textView.compoundDrawablePadding = context.dp(Spacing.SM)
                return textView
            }

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View = row(convertView, parent, position, selectedValue = true)

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View = row(convertView, parent, position, selectedValue = false)
        }

    return Spinner(context, Spinner.MODE_DROPDOWN).apply {
        this.adapter = adapter
        background =
            ripple(
                strokeBg(colors.surfaceVariant, radiusPx, colors.outlineVariant, context.dp(2)),
                radiusPx,
                colors.onSurface,
            )
        setPopupBackgroundDrawable(roundedBg(colors.elevation3, radiusPx))
        dropDownVerticalOffset = context.dp(Spacing.XS)
    }
}
