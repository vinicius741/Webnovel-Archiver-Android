package com.vinicius741.webnovelarchiver.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// ---- container builders (operate on any ViewGroup) ----

/** Fixed vertical gap inserted between siblings in a vertical [LinearLayout] body, e.g. between a
 *  section label and the row of chips below it. [heightDp] is in density-independent pixels. */
internal fun ViewGroup.spacer(heightDp: Int = Space.SM) {
    addView(
        android.widget.Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(heightDp))
        },
    )
}

internal fun ViewGroup.row(
    gravity: Int = Gravity.CENTER_VERTICAL,
    block: LinearLayout.() -> Unit,
): LinearLayout {
    val h =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            block()
        }
    addView(
        h,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(Space.SM)
        },
    )
    return h
}

internal fun ViewGroup.flow(
    spacing: Int = Space.MD,
    block: WrapLayout.() -> Unit,
): WrapLayout {
    val f =
        WrapLayout(context).apply {
            horizontalSpacingDp = spacing
            verticalSpacingDp = spacing
            block()
        }
    addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return f
}

internal fun ViewGroup.grid(
    columns: Int = 2,
    spacing: Int = Space.MD,
    block: GridLayout.() -> Unit,
): GridLayout {
    val g =
        GridLayout(context).apply {
            columnCount = columns
            horizontalSpacingDp = spacing
            verticalSpacingDp = spacing
            block()
        }
    addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return g
}

internal fun ViewGroup.card(
    elevation: Int = 1,
    block: LinearLayout.() -> Unit,
): LinearLayout {
    val c = makeCard(context, elevation)
    c.block()
    c.layoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(Space.MD)
        }
    return c
}

internal fun ViewGroup.section(title: String): TextView {
    val tv = makeSectionHeader(context, title)
    addView(tv)
    return tv
}

internal fun ViewGroup.divider() = addView(makeDivider(context))

// ---- leaf builders ----

internal fun ViewGroup.text(
    value: CharSequence,
    type: Type = Type.BODY_MEDIUM,
    color: Int? = null,
): TextView {
    val tv = makeText(context, value, type, color ?: ThemeManager.colors.onSurface)
    addView(tv)
    return tv
}

internal fun ViewGroup.button(
    label: String,
    variant: Btn = Btn.THEME_DEFAULT,
    icon: Int = 0,
    enabled: Boolean = true,
    action: () -> Unit,
): Button {
    val b = makeButton(context, label, variant, icon, action)
    if (!enabled) disableButton(b)
    val parent = this
    if (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL) {
        addView(
            b,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (parent.childCount > 0) marginStart = context.dp(Space.SM)
            },
        )
    } else {
        addView(b)
    }
    return b
}

/** Full-width (MATCH_PARENT) button with optional top/bottom margins, for stacked primary actions like
 *  Sync / Download All on the details screen. */
internal fun ViewGroup.fullButton(
    label: String,
    variant: Btn = Btn.THEME_DEFAULT,
    icon: Int = 0,
    enabled: Boolean = true,
    topMarginDp: Int = 0,
    bottomMarginDp: Int = Space.MD,
    action: () -> Unit,
): Button {
    val b = makeButton(context, label, variant, icon, action)
    if (!enabled) disableButton(b)
    val lp =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topMarginDp)
            bottomMargin = context.dp(bottomMarginDp)
        }
    addView(b, lp)
    return b
}

/** Standalone full-width button factory for containers built outside the ViewGroup DSL receiver
 *  (e.g. an info panel assembled before being added to the screen). `bottomMarginPx` is in pixels. */
internal fun makeFullWidthButton(
    context: android.content.Context,
    label: String,
    variant: Btn = Btn.THEME_DEFAULT,
    icon: Int = 0,
    bottomMarginPx: Int = 0,
    enabled: Boolean = true,
    action: () -> Unit,
): Button {
    val b = makeButton(context, label, variant, icon, action)
    if (!enabled) disableButton(b)
    b.layoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = bottomMarginPx
        }
    return b
}

/** Visually + functionally disable a themed button: drop input and fade it. Our custom
 *  background drawables don't honour the default disabled state, so we apply alpha manually. */
internal fun disableButton(b: Button) {
    b.isEnabled = false
    b.alpha = 0.4f
}

internal fun ViewGroup.chip(
    label: String,
    selected: Boolean = false,
    action: () -> Unit,
) {
    addView(makeChip(context, label, selected, action))
}

internal fun ViewGroup.labeledField(
    label: String,
    value: String,
    inputType: Int,
    hint: String? = null,
): EditText {
    addView(
        makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(context.dp(2), context.dp(Space.SM), context.dp(2), context.dp(Space.XS))
        },
    )
    val field = makeField(context, value, hint ?: label, inputType)
    addView(field)
    return field
}

/** Navigational settings row: leading icon + title (+ optional description), whole row tappable. */
internal fun ViewGroup.settingRow(
    iconRes: Int,
    title: String,
    description: String? = null,
    onClick: () -> Unit,
) {
    addView(makeSettingRow(context, iconRes, title, description, onClick))
}

/** Like [settingRow] but with an inline spinner-in-icon-slot + dim while [loading] is true.
 *  Returns the row and a [SettingRowLoadingController] to flip the state without rebuilding. */
internal fun ViewGroup.settingRowWithLoading(
    iconRes: Int,
    title: String,
    description: String? = null,
    loading: Boolean = false,
    onClick: () -> Unit,
): Pair<LinearLayout, SettingRowLoadingController> {
    val (row, controller) = makeSettingRowWithLoading(context, iconRes, title, description, onClick, loading)
    addView(row)
    return row to controller
}
