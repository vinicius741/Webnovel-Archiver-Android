package com.vinicius741.webnovelarchiver.feature.settings

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.narration.makeListGroup
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperHandle
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.stepperRow
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// v3 list rows shared by the Settings pages: rounded list groups with hairlines (the Listen hub's
// list style, reusing feature/narration's makeListGroup / makeListRow) and the navigation, stepper,
// switch and picker rows that go inside them.

/** Adds a rounded list group; rows added inside get hairlines between them. */
internal fun ViewGroup.settingsGroup(block: LinearLayout.() -> Unit): LinearLayout {
    val group = makeListGroup(context)
    group.block()
    addView(group)
    return group
}

/** A navigational row: tinted leading icon, title, one-line current value, and a chevron. */
@Suppress("LongParameterList") // Row DSL; call sites use named arguments.
internal fun LinearLayout.navRow(
    icon: Int,
    title: CharSequence,
    supporting: CharSequence? = null,
    tint: Int = ThemeManager.colors.primary,
    chevron: Boolean = true,
    onClick: () -> Unit,
): LinearLayout =
    makeListRow(context, title, supporting, leadIcon = icon, leadTint = tint, chevron = chevron, onClick = onClick).also { addView(it) }

/** A stepper row padded to sit inside a list group. */
internal fun LinearLayout.groupStepper(
    title: String,
    hint: String?,
    initial: Double,
    spec: StepperSpec,
    onChange: (Double) -> Double?,
): StepperHandle {
    val holder =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(Spacing.LG), 0, context.dp(Spacing.MD), 0)
        }
    val handle = holder.stepperRow(title, hint, initial, spec, onChange)
    addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return handle
}

/** A plain, non-interactive row inside a list group (e.g. "Pitch: not available"). */
internal fun LinearLayout.infoRow(
    title: String,
    supporting: String?,
) {
    addView(makeListRow(context, title, supporting))
}

/** The v3 switch: gold track when on. It does not take touches itself; its row toggles it. */
internal fun makeV3Switch(
    context: Context,
    checked: Boolean,
): SwitchCompat {
    val colors = ThemeManager.colors
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    return SwitchCompat(context).apply {
        isChecked = checked
        thumbTintList = ColorStateList(states, intArrayOf(colors.onPrimary, colors.onSurfaceVariant))
        trackTintList = ColorStateList(states, intArrayOf(colors.primary, colors.elevation5))
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** A row with a trailing switch; tapping anywhere on it flips the switch and reports the new value. */
@Suppress("LongParameterList") // Row DSL; call sites use named arguments.
internal fun LinearLayout.switchRow(
    title: String,
    supporting: String? = null,
    checked: Boolean,
    icon: Int = 0,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
): SwitchCompat {
    val switch = makeV3Switch(context, checked)
    lateinit var row: LinearLayout
    row =
        makeListRow(context, title, supporting, leadIcon = icon, trailing = switch) {
            switch.isChecked = !switch.isChecked
            row.contentDescription = "$title, ${if (switch.isChecked) "on" else "off"}"
            onToggle(switch.isChecked)
        }
    row.contentDescription = "$title, ${if (checked) "on" else "off"}"
    row.isEnabled = enabled
    row.alpha = if (enabled) 1f else DISABLED_ALPHA
    addView(row)
    return switch
}

/** A picker row: small label above the chosen value, with a trailing expand arrow. */
internal fun LinearLayout.valueRow(
    label: String,
    value: String,
    icon: Int,
    onClick: () -> Unit,
) {
    val colors = ThemeManager.colors
    val arrow =
        ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_down, colors.faint))
            layoutParams =
                LinearLayout.LayoutParams(context.dp(ARROW_DP), context.dp(ARROW_DP)).apply { marginEnd = context.dp(Spacing.XS) }
        }
    val row = makeListRow(context, label, value, leadIcon = icon, trailing = arrow, onClick = onClick)
    // Label small and muted on top, the value bold below it.
    val texts = row.getChildAt(1) as? LinearLayout
    (texts?.getChildAt(0) as? TextView)?.apply {
        textSize = LABEL_SP
        setTextColor(colors.onSurfaceVariant)
        setTypeface(android.graphics.Typeface.DEFAULT)
    }
    (texts?.getChildAt(1) as? TextView)?.apply {
        textSize = VALUE_SP
        setTextColor(colors.onSurface)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    row.contentDescription = "$label: $value"
    addView(row)
}

private const val DISABLED_ALPHA = 0.4f
private const val ARROW_DP = 20
private const val VALUE_SP = 15f
private const val LABEL_SP = 12.5f
