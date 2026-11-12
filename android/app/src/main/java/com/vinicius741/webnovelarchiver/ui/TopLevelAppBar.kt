package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

// v3 top-level destination chrome (Library, Updates): a large title over the background, with
// optional icon actions. Screens that use it render with ScreenChrome.IMMERSIVE and add it first.

private const val LARGE_TITLE_SP = 26f
private const val ACTION_DOT_DP = 8

/** A large app bar; [setSubtitle] updates the count line and [setDot] an action's dot in place. */
internal class TopLevelAppBar(
    val view: LinearLayout,
    private val subtitleView: TextView,
    private val dots: Map<String, View>,
) {
    fun setSubtitle(text: String?) {
        subtitleView.text = text.orEmpty()
        subtitleView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setDot(
        actionLabel: String,
        on: Boolean,
    ) {
        dots[actionLabel]?.visibility = if (on) View.VISIBLE else View.GONE
    }
}

/**
 * Builds the large app bar. Each action in [actions] is an icon button; labels in [dotted] get a
 * small gold dot (e.g. "filters are on").
 */
internal fun ScreenHost.makeTopLevelAppBar(
    title: String,
    subtitle: String?,
    actions: List<AppBarAction>,
    dotted: Set<String> = emptySet(),
): TopLevelAppBar {
    val colors = ThemeManager.colors
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.background)
            minimumHeight = systemBarTop() + dp(TOP_BAR_HEIGHT_DP)
            setPadding(dp(SCREEN_SIDE_PADDING_DP), systemBarTop() + dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS))
        }
    val subtitleView =
        makeText(app, subtitle.orEmpty(), Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            includeFontPadding = false
            typeface = Typeface.create(typeface, Typeface.BOLD)
            maxLines = 1
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    val titles =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, title, Type.HEADLINE, colors.onSurface).apply {
                    textSize = LARGE_TITLE_SP
                    includeFontPadding = false
                    letterSpacing = -0.015f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            addView(
                subtitleView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(2)
                },
            )
        }
    bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val dots = mutableMapOf<String, View>()
    actions.forEach { action ->
        val button = appBarActionButton(app, action)
        bar.addView(button.view)
        dots[action.label] = button.dot
        button.dot.visibility = if (action.label in dotted) View.VISIBLE else View.GONE
    }
    return TopLevelAppBar(bar, subtitleView, dots)
}

/** An app-bar icon button and its (initially hidden) gold dot. */
internal class AppBarActionButton(
    val view: View,
    val dot: View,
)

/** A 48dp app-bar icon button with a gold dot at its top-end corner, hidden until shown. */
internal fun appBarActionButton(
    context: Context,
    action: AppBarAction,
    dotRingColor: Int = ThemeManager.colors.background,
): AppBarActionButton {
    val size = context.dp(TOP_BAR_ACTION_DP)
    val icon =
        context.iconButton(action.icon, action.label, action.tint ?: ThemeManager.colors.onSurface) { action.onClick() }.apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setPadding(context.dp(Spacing.MD), context.dp(Spacing.MD), context.dp(Spacing.MD), context.dp(Spacing.MD))
        }
    val dot =
        View(context).apply {
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ThemeManager.colors.primary)
                    setStroke(context.dp(2), dotRingColor)
                }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
    val frame =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(icon)
            addView(
                dot,
                FrameLayout.LayoutParams(context.dp(ACTION_DOT_DP + 4), context.dp(ACTION_DOT_DP + 4), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, context.dp(Spacing.SM + 1), context.dp(Spacing.SM + 1), 0)
                },
            )
        }
    return AppBarActionButton(frame, dot)
}

/**
 * A rounded group of rows separated by hairlines (the v3 `.list`): add rows as children. Rows draw
 * their own press feedback; the group clips them to its corners.
 */
internal fun makeListGroup(context: Context): LinearLayout {
    val colors = ThemeManager.colors
    val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = strokeBg(colors.elevation1, radius, colors.line, context.dp(1).coerceAtLeast(1))
        clipToOutline = true
        dividerDrawable =
            GradientDrawable().apply {
                setColor(colors.line)
                setSize(1, context.dp(1).coerceAtLeast(1))
            }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

/** A small indeterminate gold spinner, [sizeDp] square. */
internal fun makeSmallSpinner(
    context: Context,
    sizeDp: Int = 20,
): ProgressBar =
    ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

/** The v3 eyebrow: a small uppercase gold label above a card's headline. */
internal fun makeEyebrow(
    context: Context,
    label: String,
): TextView =
    makeText(context, label, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
        isAllCaps = true
        letterSpacing = 0.07f
        typeface = Typeface.create(typeface, Typeface.BOLD)
    }

/** The v3 tinted hero card (`.card.tint`): a warm gold-to-surface gradient with a faint gold border. */
internal fun makeTintedCard(context: Context): LinearLayout {
    val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = context.dp(Spacing.LG)
        setPadding(pad, pad, pad, pad)
        background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(TINT_CARD_START, TINT_CARD_END, TINT_CARD_END)).apply {
                cornerRadius = radius
                setStroke(
                    context.dp(1).coerceAtLeast(1),
                    androidx.core.graphics.ColorUtils
                        .setAlphaComponent(ThemeManager.colors.primary, TINT_CARD_BORDER_ALPHA),
                )
            }
    }
}

/** Height (dp) of the large app bar below the status bar. */
internal const val TOP_BAR_HEIGHT_DP = 64
private const val TOP_BAR_ACTION_DP = 48
private const val TINT_CARD_START = 0xFF2C2414.toInt()
private const val TINT_CARD_END = 0xFF1E1B15.toInt()
private const val TINT_CARD_BORDER_ALPHA = 0x2E
