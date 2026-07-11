package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R

// ------------------------------------------------------------------
// Cards / surfaces
// ------------------------------------------------------------------

/**
 * A themed surface card. Uses the theme's elevation surface color, rounded
 * corners, and either a drop shadow (SHADOW styles) or a thin outline border
 * (Midnight / BORDER style).
 */
fun makeCard(
    context: Context,
    elevationLevel: Int = 1,
): LinearLayout {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.cardRadius).toFloat()
    val surfaceColor =
        when (elevationLevel.coerceIn(0, 5)) {
            0 -> colors.surface
            1 -> colors.elevation1
            2 -> colors.elevation2
            3 -> colors.elevation3
            4 -> colors.elevation4
            else -> colors.elevation5
        }
    val pad = context.dp(Space.LG)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        background =
            when (t.shapes.elevationStyle) {
                ElevationStyle.BORDER -> strokeBg(surfaceColor, radiusPx, colors.outline, context.dp(1))
                else ->
                    roundedBg(surfaceColor, radiusPx).also {
                        if (elevationLevel > 0) elevate(elevationLevel.toFloat() + 1f)
                    }
            }
        showDividers = LinearLayout.SHOW_DIVIDER_NONE
        clipToPadding = false
    }
}

/** Section header (e.g. "Appearance", "Downloads"). */
fun makeSectionHeader(
    context: Context,
    title: String,
): TextView =
    makeText(context, title, Type.LABEL_LARGE, ThemeManager.colors.primary).apply {
        setPadding(context.dp(2), context.dp(Space.MD + 2), context.dp(2), context.dp(Space.XS))
        letterSpacing = 0.08f
    }

/** Thin divider line using the theme's outline-variant color. */
fun makeDivider(context: Context): View =
    View(context).apply {
        setBackgroundColor(ThemeManager.colors.outlineVariant)
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                setMargins(0, context.dp(Space.SM), 0, context.dp(Space.SM))
            }
    }

/**
 * A flat, navigational settings row: a leading icon tinted to the surface-variant color, a vertical
 * column of title + optional description, and a themed ripple across the whole row. No trailing
 * chevron — leading icons only. Used for every non-Appearance entry on the Settings page.
 */
fun makeSettingRow(
    context: Context,
    iconRes: Int,
    title: CharSequence,
    description: CharSequence? = null,
    onClick: () -> Unit,
): LinearLayout {
    val t = ThemeManager.current
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
        background = selectableRipple(t.colors.surface)
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(Space.XS)
            }
        // Leading icon, sized to match the Material list-item leading icon (~24dp).
        addView(
            makeSettingRowIcon(context, iconRes),
            LinearLayout.LayoutParams(context.dp(24), context.dp(24)),
        )
        addView(
            makeSettingRowTextColumn(context, title, description),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(Space.MD)
            },
        )
        setOnClickListener { onClick() }
    }
}

/**
 * A settings row that can show an inline indeterminate spinner in its leading-icon slot while a
 * background action runs. While [loading] is true the spinner replaces the icon, the whole row is
 * dimmed (matching [disableButton]), and taps are ignored. Returns a controller to flip the state
 * from the caller's coroutine without rebuilding the row.
 */
fun makeSettingRowWithLoading(
    context: Context,
    iconRes: Int,
    title: CharSequence,
    description: CharSequence? = null,
    onClick: () -> Unit,
    loading: Boolean = false,
): Pair<LinearLayout, SettingRowLoadingController> {
    val t = ThemeManager.current
    // Icon slot: a FrameLayout so the icon and spinner overlay in the same 24dp box and we can
    // toggle between them without touching the row's child indices.
    val iconSlot =
        FrameLayout(context).apply {
            val icon =
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(iconRes, t.colors.onSurfaceVariant))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
                }
            val spinner =
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(t.colors.primary)
                    isIndeterminate = true
                    // ProgressBar has its own padding; inset it so the 24dp box matches the icon size.
                    setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                    layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24))
                    visibility = if (loading) View.VISIBLE else View.GONE
                }
            addView(icon)
            addView(spinner)
        }
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = !loading
            isFocusable = !loading
            setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
            background = selectableRipple(t.colors.surface)
            if (loading) alpha = 0.4f
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(Space.XS)
                }
            addView(iconSlot, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(
                makeSettingRowTextColumn(context, title, description),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(Space.MD)
                },
            )
        }
    val controller =
        SettingRowLoadingController(
            row = row,
            spinner = iconSlot.getChildAt(1) as ProgressBar,
            onClick = onClick,
        )
    if (!loading) row.setOnClickListener { controller.click() }
    return row to controller
}

private fun makeSettingRowIcon(
    context: Context,
    iconRes: Int,
): ImageView =
    ImageView(context).apply {
        setImageDrawable(context.tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

private fun makeSettingRowTextColumn(
    context: Context,
    title: CharSequence,
    description: CharSequence?,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            TextView(context).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.colors.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            },
        )
        description?.let {
            addView(
                TextView(context).apply {
                    text = it
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                    setTextColor(ThemeManager.colors.onSurfaceVariant)
                    setPadding(0, context.dp(2), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
            )
        }
    }

/**
 * Toggles the inline loading state of a [makeSettingRowWithLoading] row from the caller's
 * coroutine, without rebuilding the view. While loading the spinner replaces the icon, the row
 * dims to match [disableButton], and taps are dropped so the action can't be re-triggered.
 */
class SettingRowLoadingController(
    private val row: LinearLayout,
    private val spinner: ProgressBar,
    private val onClick: () -> Unit,
) {
    private var loading = spinner.visibility == View.VISIBLE

    init {
        if (!loading) row.setOnClickListener { click() }
    }

    fun click() {
        if (!loading) onClick()
    }

    /** Shows/hides the spinner and enables/disables the row. */
    fun setLoading(loading: Boolean) {
        if (loading == this.loading) return
        this.loading = loading
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        row.alpha = if (loading) 0.4f else 1f
        row.isClickable = !loading
        row.isFocusable = !loading
        if (loading) {
            row.setOnClickListener(null)
        } else {
            row.setOnClickListener { click() }
        }
    }
}

/*
 * ------------------------------------------------------------------
 * Selectable card row — a card surface with an embedded checkbox. Used by the
 * selection screens (Select Novels / Select Chapters) so multi-select reuses the
 * library's card design instead of dropping to bare CheckBoxes.
 * ------------------------------------------------------------------
 */

fun makeSelectableCardRow(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence? = null,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
): LinearLayout {
    val t = ThemeManager.current
    val radiusPx = context.dp(t.shapes.cardRadius).toFloat()
    val cb =
        CheckBox(context).apply {
            isChecked = selected
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            applyCheckBoxTint()
        }
    val textCol =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(t.colors.onSurface)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
            )
            subtitle?.let {
                addView(
                    TextView(context).apply {
                        text = it
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                        setTextColor(t.colors.onSurfaceVariant)
                        setPadding(0, context.dp(2), 0, 0)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    },
                )
            }
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
        background = roundedBg(t.colors.elevation1, radiusPx)
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(Space.MD)
            }
        addView(cb)
        addView(
            textCol,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(Space.MD)
            },
        )
        // Tapping the row (not just the checkbox) toggles selection.
        isClickable = true
        isFocusable = true
        setOnClickListener { cb.isChecked = !cb.isChecked }
    }
}

// ------------------------------------------------------------------
// Empty state
// ------------------------------------------------------------------

/**
 * A calm, contextual empty state. The icon sits inside a soft tinted disc (so it reads as an
 * intentional illustration instead of a lone glyph floating in whitespace), an optional bold
 * [title] leads the message, and an optional [actionLabel]/[onAction] renders a tonal CTA when
 * there's an obvious next step (e.g. "Add a story"). All optional params default so legacy call
 * sites keep working unchanged.
 */
fun makeEmptyState(
    context: Context,
    message: String,
    iconRes: Int = R.drawable.wna_book_open,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
): View {
    val t = ThemeManager.current
    val colors = t.colors
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(Space.XL), context.dp(Space.XL + Space.LG), context.dp(Space.XL), context.dp(Space.XL + Space.LG))
        // Soft tinted disc anchoring the icon. primaryContainer/onPrimaryContainer gives every
        // theme (Obsidian gold, Midnight blue, Forest green, Classic navy) a recognisable accent
        // without overpowering the surrounding surface.
        val discSize = context.dp(80)
        val iconSize = context.dp(32)
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                background = roundedBg(colors.primaryContainer, discSize / 2f)
                addView(
                    ImageView(context).apply {
                        setImageDrawable(context.tintedIcon(iconRes, colors.onPrimaryContainer))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    },
                    LinearLayout.LayoutParams(iconSize, iconSize),
                )
            },
            LinearLayout.LayoutParams(discSize, discSize).apply {
                bottomMargin = context.dp(Space.LG)
            },
        )
        title?.let {
            addView(
                makeText(context, it, Type.TITLE_MEDIUM, colors.onSurface).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        // Keep the legacy look for title-less call sites: BODY_LARGE reads as the primary line.
        // Under a title the message becomes the supporting subtitle, so it drops to BODY_MEDIUM.
        val messageType = if (title != null) Type.BODY_MEDIUM else Type.BODY_LARGE
        addView(
            makeText(context, message, messageType, colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                if (title != null) setPadding(0, context.dp(Space.XS), 0, 0)
                maxLines = 4
            },
        )
        if (actionLabel != null && onAction != null) {
            addView(
                makeButton(context, actionLabel, Btn.TONAL) { onAction() },
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = context.dp(Space.LG) },
            )
        }
    }
}
