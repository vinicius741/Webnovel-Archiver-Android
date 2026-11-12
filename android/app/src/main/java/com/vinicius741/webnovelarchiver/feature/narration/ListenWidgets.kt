package com.vinicius741.webnovelarchiver.feature.narration

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// Small building blocks shared by the Listen hub's sections: a gold section label with an optional
// trailing aside (text or action), a rounded list group with hairlines between rows, and its rows.

/** An uppercase gold section label; [aside] is muted text, or a gold action when [onAside] is set. */
internal fun ViewGroup.listenSectionLabel(
    title: String,
    aside: String? = null,
    onAside: (() -> Unit)? = null,
) {
    val colors = ThemeManager.colors
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(SECTION_MIN_HEIGHT_DP)
            setPadding(context.dp(Spacing.XS), context.dp(Spacing.LG + 2), context.dp(Spacing.XS), context.dp(Spacing.SM))
        }
    row.addView(
        makeText(context, title, Type.LABEL_MEDIUM, colors.primary).apply {
            isAllCaps = true
            letterSpacing = SECTION_SPACING
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    aside?.let {
        row.addView(
            makeText(
                context,
                it,
                if (onAside !=
                    null
                ) {
                    Type.LABEL_LARGE
                } else {
                    Type.BODY_SMALL
                },
                if (onAside != null) colors.primary else colors.onSurfaceVariant,
            ).apply {
                if (onAside != null) {
                    setPadding(context.dp(Spacing.SM), context.dp(Spacing.XS), context.dp(Spacing.SM), context.dp(Spacing.XS))
                    background = selectableRipple(colors.primary)
                    setOnClickListener { onAside() }
                }
            },
        )
    }
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** A muted one-line hint under a section label. */
internal fun ViewGroup.listenHint(text: String) {
    addView(
        makeText(context, text, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(context.dp(Spacing.XS), 0, context.dp(Spacing.XS), context.dp(Spacing.SM + 2))
        },
    )
}

/** A rounded, hairline-bordered list; rows added to it get hairlines between them. */
internal fun makeListGroup(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
        background = strokeBg(ThemeManager.colors.elevation1, radius, ThemeManager.colors.line, context.dp(1).coerceAtLeast(1))
        roundCorners(ThemeManager.shapes.cardRadius.toFloat())
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(ThemeManager.colors.line)
                setSize(1, context.dp(1).coerceAtLeast(1))
            }
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

/**
 * A list row: [lead] (an icon in a tinted square, or any view such as a cover), a title with an
 * optional supporting line, and an optional [trailing] view. Tappable when [onClick] is set.
 */
@Suppress("LongParameterList") // Row DSL; call sites use named arguments.
internal fun makeListRow(
    context: Context,
    title: CharSequence,
    supporting: CharSequence? = null,
    leadIcon: Int = 0,
    leadTint: Int = ThemeManager.colors.primary,
    lead: View? = null,
    trailing: View? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val colors = ThemeManager.colors
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
        setPadding(context.dp(Spacing.LG), context.dp(Spacing.SM + 2), context.dp(Spacing.SM), context.dp(Spacing.SM + 2))
        val leading =
            lead ?: leadIcon.takeIf { it != 0 }?.let { icon ->
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(icon, leadTint))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = roundedBg(colors.elevation3, context.dp(LEAD_RADIUS_DP).toFloat())
                    layoutParams = LinearLayout.LayoutParams(context.dp(LEAD_SIZE_DP), context.dp(LEAD_SIZE_DP))
                }
            }
        leading?.let {
            addView(it)
            (it.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = context.dp(Spacing.MD + 2)
        }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(rowTitle(context, title))
                supporting?.let { addView(rowSupporting(context, it)) }
            }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Spacing.XS) })
        trailing?.let { addView(it) }
        if (chevron) {
            addView(
                ImageView(context).apply { setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_right, colors.faint)) },
                LinearLayout.LayoutParams(context.dp(CHEVRON_DP), context.dp(CHEVRON_DP)).apply { marginEnd = context.dp(Spacing.XS) },
            )
        }
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
            setOnClickListener { onClick() }
        }
    }
}

private fun rowTitle(
    context: Context,
    title: CharSequence,
): TextView =
    makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
        text = title
        textSize = ROW_TITLE_SP
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

private fun rowSupporting(
    context: Context,
    text: CharSequence,
): TextView =
    makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        this.text = text
        setPadding(0, context.dp(2), 0, 0)
    }

private const val SECTION_MIN_HEIGHT_DP = 40
private const val SECTION_SPACING = 0.06f
private const val ROW_MIN_HEIGHT_DP = 60
private const val ROW_TITLE_SP = 15f
private const val LEAD_SIZE_DP = 40
private const val LEAD_RADIUS_DP = 12
private const val CHEVRON_DP = 20
