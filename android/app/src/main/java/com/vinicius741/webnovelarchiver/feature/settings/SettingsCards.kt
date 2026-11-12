package com.vinicius741.webnovelarchiver.feature.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// v3 cards and small pieces shared by the Settings and Downloads pages: tinted cards, eyebrows,
// pills, stat grids, notes, and row icon buttons.

/** A small rounded status pill; [dot] adds a leading dot in the text color. */
internal fun makePill(
    context: Context,
    text: String,
    background: Int,
    foreground: Int,
    dot: Boolean = false,
): TextView =
    makeText(context, if (dot) "● $text" else text, Type.LABEL_SMALL, foreground).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(Spacing.SM), context.dp(3), context.dp(Spacing.SM), context.dp(3))
        this.background = roundedBg(background, context.dp(PILL_RADIUS_DP).toFloat())
        maxLines = 1
    }

/** A card washed with [tint] (gold for attention, error for failures, sage for done). */
internal fun tintedCardBackground(
    context: Context,
    tint: Int,
): Drawable {
    val colors = ThemeManager.colors
    return strokeBg(
        ColorUtils.blendARGB(colors.elevation1, tint, TINT_FILL),
        context.dp(ThemeManager.shapes.cardRadius).toFloat(),
        ColorUtils.blendARGB(colors.line, tint, TINT_STROKE),
        context.dp(1).coerceAtLeast(1),
    )
}

/** A card with a tinted wash; children go in [block]. */
internal fun ViewGroup.tintedCard(
    tint: Int,
    block: LinearLayout.() -> Unit,
): LinearLayout {
    val card =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(Spacing.LG)
            setPadding(pad, pad, pad, pad)
            background = tintedCardBackground(context, tint)
        }
    card.block()
    addView(
        card,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(Spacing.MD)
        },
    )
    return card
}

/** An uppercase label above a card's title ("Verification needed", "Saved TTS session"). */
internal fun makeEyebrow(
    context: Context,
    text: String,
    color: Int = ThemeManager.colors.primary,
): TextView =
    makeText(context, text, Type.LABEL_SMALL, color).apply {
        isAllCaps = true
        letterSpacing = EYEBROW_SPACING
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, context.dp(Spacing.XS))
    }

/** A leading icon beside a column; returns the column for the caller to fill. */
internal fun ViewGroup.iconColumnRow(
    icon: Int,
    tint: Int,
    gravity: Int = Gravity.TOP,
): LinearLayout {
    val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            addView(
                ImageView(context).apply { setImageDrawable(context.tintedIcon(icon, tint)) },
                LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply { marginEnd = context.dp(Spacing.MD + 2) },
            )
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return column
}

/** A row of big numbers with small labels under them; returns the number views to patch later. */
internal fun ViewGroup.statGrid(labels: List<String>): List<TextView> {
    val colors = ThemeManager.colors
    val values = mutableListOf<TextView>()
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(Spacing.SM + 2), 0, 0)
            labels.forEach { label ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val value =
                            makeText(context, "–", Type.TITLE_LARGE, colors.onSurface).apply {
                                textSize = STAT_SP
                                fontFeatureSettings = "tnum"
                            }
                        values += value
                        addView(value)
                        addView(
                            makeText(context, label, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, context.dp(2), 0, 0) },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return values
}

/** A muted note under a group. */
internal fun ViewGroup.settingsNote(text: String): TextView {
    val note =
        makeText(context, text, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(context.dp(Spacing.XS), context.dp(Spacing.SM + 2), context.dp(Spacing.XS), 0)
        }
    addView(note)
    return note
}

/** A ripple-backed icon button sized for list rows and card corners. */
internal fun makeRowIconButton(
    context: Context,
    icon: Int,
    description: String,
    tint: Int = ThemeManager.colors.onSurfaceVariant,
    onClick: () -> Unit,
): ImageView =
    ImageView(context).apply {
        contentDescription = description
        setImageDrawable(context.tintedIcon(icon, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = context.dp(Spacing.SM + 1)
        setPadding(pad, pad, pad, pad)
        background = selectableRipple(ThemeManager.colors.onSurface)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(context.dp(ROW_ICON_BUTTON_DP), context.dp(ROW_ICON_BUTTON_DP))
    }

private const val PILL_RADIUS_DP = 11
private const val TINT_FILL = 0.32f
private const val TINT_STROKE = 0.45f
private const val EYEBROW_SPACING = 0.08f
private const val ICON_DP = 24
private const val STAT_SP = 20f
private const val ROW_ICON_BUTTON_DP = 42
