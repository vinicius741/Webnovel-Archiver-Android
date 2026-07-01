package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
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
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(iconRes, t.colors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(context.dp(24), context.dp(24)),
        )
        // Title (+ optional description) column.
        addView(
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
                description?.let {
                    addView(
                        TextView(context).apply {
                            text = it
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                            setTextColor(t.colors.onSurfaceVariant)
                            setPadding(0, context.dp(2), 0, 0)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            includeFontPadding = false
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(Space.MD)
            },
        )
        setOnClickListener { onClick() }
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
