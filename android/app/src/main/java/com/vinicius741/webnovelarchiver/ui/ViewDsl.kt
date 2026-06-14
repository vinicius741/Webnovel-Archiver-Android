package com.vinicius741.webnovelarchiver.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/* ---- container builders (operate on any ViewGroup) ---- */

internal fun ViewGroup.row(gravity: Int = Gravity.CENTER_VERTICAL, block: LinearLayout.() -> Unit): LinearLayout {
    val h = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        block()
    }
    addView(h, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return h
}

internal fun ViewGroup.flow(spacing: Int = 8, block: WrapLayout.() -> Unit): WrapLayout {
    val f = WrapLayout(context).apply {
        horizontalSpacingDp = spacing
        verticalSpacingDp = spacing
        block()
    }
    addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return f
}

internal fun ViewGroup.grid(columns: Int = 2, spacing: Int = 8, block: GridLayout.() -> Unit): GridLayout {
    val g = GridLayout(context).apply {
        columnCount = columns
        horizontalSpacingDp = spacing
        verticalSpacingDp = spacing
        block()
    }
    addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return g
}

internal fun ViewGroup.card(elevation: Int = 1, block: LinearLayout.() -> Unit): LinearLayout {
    val c = makeCard(context, elevation)
    c.block()
    c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = context.dp(10)
    }
    return c
}

internal fun ViewGroup.section(title: String): TextView {
    val tv = makeSectionHeader(context, title)
    addView(tv)
    return tv
}

internal fun ViewGroup.divider() = addView(makeDivider(context))

/* ---- leaf builders ---- */

internal fun ViewGroup.text(value: CharSequence, type: Type = Type.BODY_MEDIUM, color: Int? = null): TextView {
    val tv = makeText(context, value, type, color ?: ThemeManager.colors.onSurface)
    addView(tv)
    return tv
}

internal fun ViewGroup.button(label: String, variant: Btn = Btn.THEME_DEFAULT, icon: Int = 0, action: () -> Unit): Button {
    val b = makeButton(context, label, variant, icon, action)
    addView(b)
    return b
}

/** Full-width (MATCH_PARENT) button with a bottom margin, for stacked primary actions like
 *  Sync / Download All on the details screen. */
internal fun ViewGroup.fullButton(
    label: String,
    variant: Btn = Btn.THEME_DEFAULT,
    icon: Int = 0,
    bottomMarginDp: Int = 10,
    action: () -> Unit,
): Button {
    val b = makeButton(context, label, variant, icon, action)
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = context.dp(bottomMarginDp)
    }
    addView(b, lp)
    return b
}

internal fun ViewGroup.chip(label: String, selected: Boolean = false, action: () -> Unit) {
    addView(makeChip(context, label, selected, action))
}

internal fun ViewGroup.labeledField(label: String, value: String, inputType: Int, hint: String? = null): EditText {
    addView(makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(4))
    })
    val field = makeField(context, value, hint ?: label, inputType)
    addView(field)
    return field
}
