package com.vinicius741.webnovelarchiver.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Range and formatting of a numeric setting shown with a [makeStepper]. Values are doubles so one
 * widget covers both whole counts (chapters, ms) and decimals (speech rate); [decimals] rounds them.
 * [zeroLabel] replaces the formatted value at 0 (e.g. "Off").
 */
internal data class StepperSpec(
    val step: Double,
    val min: Double,
    val max: Double,
    val unit: String = "",
    val decimals: Int = 0,
    val zeroLabel: String? = null,
    /** Accessible name and the title of the type-a-value dialog. */
    val label: String,
) {
    fun clamp(value: Double): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return ((value.coerceIn(min, max) * factor).roundToLong() / factor)
    }

    fun format(value: Double): String =
        if (zeroLabel != null && value == 0.0) {
            zeroLabel
        } else {
            String.format(Locale.US, "%.${decimals}f", value) + unit
        }
}

/** A stepper's live handle: read or set the shown value without firing its listener. */
internal class StepperHandle(
    val view: View,
    private val spec: StepperSpec,
    private val valueText: TextView,
    private val minus: View,
    private val plus: View,
    initial: Double,
) {
    var value: Double = spec.clamp(initial)
        private set

    fun show(newValue: Double) {
        value = spec.clamp(newValue)
        valueText.text = spec.format(value)
        minus.isEnabled = value > spec.min
        minus.alpha = if (minus.isEnabled) 1f else DISABLED_ALPHA
        plus.isEnabled = value < spec.max
        plus.alpha = if (plus.isEnabled) 1f else DISABLED_ALPHA
    }
}

private const val DISABLED_ALPHA = 0.3f
private const val HOLD_FIRST_DELAY_MS = 420L
private const val HOLD_SLOW_DELAY_MS = 140L
private const val HOLD_FAST_DELAY_MS = 70L
private const val HOLD_SLOW_TICKS = 6
private const val HOLD_BIG_STEP_AFTER = 14
private const val HOLD_BIG_STEP_FACTOR = 5

/**
 * The −/value/+ control that replaces sliders for numeric settings. Tap ± to nudge, hold ± to
 * repeat (it speeds up, then moves in bigger steps), tap the value to type an exact number.
 * [onChange] receives every committed value; return a different number from it to override
 * (e.g. keep a minimum below a maximum), or null to accept.
 */
internal fun makeStepper(
    context: Context,
    initial: Double,
    spec: StepperSpec,
    onChange: (Double) -> Double?,
): StepperHandle {
    val colors = ThemeManager.colors
    lateinit var handle: StepperHandle

    fun commit(raw: Double) {
        val clamped = spec.clamp(raw)
        if (clamped == handle.value) return
        handle.show(clamped)
        onChange(clamped)?.let { handle.show(it) }
    }

    val minus = stepButton(context, R.drawable.wna_remove, "Decrease ${spec.label}") { commit(handle.value - it * spec.step) }
    val plus = stepButton(context, R.drawable.wna_add, "Increase ${spec.label}") { commit(handle.value + it * spec.step) }
    val valueText =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(colors.primary)
            fontFeatureSettings = "tnum"
            minWidth = context.dp(72)
            setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM), 0)
            background =
                ripple(roundedBg(android.graphics.Color.TRANSPARENT, context.dp(10).toFloat()), context.dp(10).toFloat(), colors.primary)
            contentDescription = "${spec.label}. Tap to type an exact value"
            setOnClickListener { promptExactValue(context, spec, handle.value) { commit(it) } }
        }
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus)
            addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(40)))
            addView(plus)
        }
    handle = StepperHandle(row, spec, valueText, minus, plus, initial)
    handle.show(initial)
    return handle
}

/**
 * A settings row: title and optional hint on the left, stepper on the right. Returns the handle so
 * a screen can update the shown value when another setting forces it.
 */
internal fun ViewGroup.stepperRow(
    title: String,
    hint: String?,
    initial: Double,
    spec: StepperSpec,
    onChange: (Double) -> Double?,
): StepperHandle {
    val handle = makeStepper(context, initial, spec, onChange)
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(60)
            setPadding(0, context.dp(Spacing.SM + 2), 0, context.dp(Spacing.SM + 2))
            val texts =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, title, Type.TITLE_SMALL, ThemeManager.colors.onSurface))
                    hint?.let { addView(makeText(context, it, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)) }
                }
            addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Spacing.MD) },
            )
            addView(handle.view)
        }
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return handle
}

/** A round ± button that fires once on press and keeps firing while held (with acceleration). */
@SuppressLint("ClickableViewAccessibility") // Accessibility clicks go through performClick → one step.
private fun stepButton(
    context: Context,
    icon: Int,
    description: String,
    onStep: (multiplier: Int) -> Unit,
): ImageView {
    val handler = Handler(Looper.getMainLooper())
    var ticks = 0
    lateinit var repeat: Runnable
    val button =
        ImageView(context).apply {
            contentDescription = description
            setImageDrawable(context.tintedIcon(icon, ThemeManager.colors.onSurface))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = context.dp(Spacing.SM)
            setPadding(pad, pad, pad, pad)
            background =
                ripple(
                    roundedBg(ThemeManager.colors.elevation3, context.dp(20).toFloat()),
                    context.dp(20).toFloat(),
                    ThemeManager.colors.primary,
                )
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        }
    repeat =
        Runnable {
            if (!button.isEnabled) return@Runnable
            // Holding: keep the gesture from turning into a scroll while values repeat.
            if (ticks == 0) button.parent?.requestDisallowInterceptTouchEvent(true)
            ticks++
            onStep(if (ticks > HOLD_BIG_STEP_AFTER) HOLD_BIG_STEP_FACTOR else 1)
            handler.postDelayed(repeat, if (ticks < HOLD_SLOW_TICKS) HOLD_SLOW_DELAY_MS else HOLD_FAST_DELAY_MS)
        }
    // A tap steps once on release; a hold starts repeating after a delay. Nothing changes on press,
    // so a scroll that starts on a ± button (it cancels the touch) never nudges the value.
    button.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!v.isEnabled) return@setOnTouchListener false
                v.isPressed = true
                ticks = 0
                handler.postDelayed(repeat, HOLD_FIRST_DELAY_MS)
                true
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                handler.removeCallbacks(repeat)
                if (ticks == 0 && v.isEnabled) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onStep(1)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                handler.removeCallbacks(repeat)
                true
            }
            else -> true
        }
    }
    // Keyboard / TalkBack activation: one step per click.
    button.setOnClickListener { onStep(1) }
    return button
}

/** The "type an exact value" dialog behind a stepper's number. Out-of-range input is clamped. */
private fun promptExactValue(
    context: Context,
    spec: StepperSpec,
    current: Double,
    onValue: (Double) -> Unit,
) {
    val input =
        makeField(
            context,
            spec.format(current).removeSuffix(spec.unit).takeIf { spec.zeroLabel == null || current != 0.0 } ?: "0",
            spec.label,
            if (spec.decimals > 0) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER,
        )
    val range = "Between ${spec.format(
        spec.min,
    ).let { if (spec.zeroLabel != null && spec.min == 0.0) "0" else it }} and ${spec.format(spec.max)}"
    val form =
        FormLayout(context, dialog = true).apply {
            addItem(
                input,
                spec.unit
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "${spec.label} ($it)" } ?: spec.label,
            )
            addItem(makeText(context, range, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
        }
    val dialog =
        AlertDialog
            .Builder(context)
            .setTitle(spec.label)
            .setView(form)
            .setPositiveButton("Set") { _, _ ->
                input.text
                    .toString()
                    .replace(',', '.')
                    .trim()
                    .toDoubleOrNull()
                    ?.let(onValue)
            }.setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyFormStyle()
    input.requestFocus()
    input.selectAll()
}
