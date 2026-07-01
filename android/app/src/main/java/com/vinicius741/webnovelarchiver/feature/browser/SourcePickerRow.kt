package com.vinicius741.webnovelarchiver.feature.browser

import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Builds the list of registered source rows, ready to append into any screen body. Each row opens the
 * existing [showBrowser] Custom Tab at that source's [SourceProvider.baseUrl]. The rows are built
 * generically over [SourceRegistry.all], so adding a future source needs no change here — it only has
 * to be registered in [SourceRegistry], per the rule in `android/AGENTS.md`.
 *
 * Reused from the legacy standalone "Browse Sources" picker; now embedded directly on the Add Story
 * screen as the "Or browse a source" affordance.
 */
internal fun sourcePickerRows(
    context: android.content.Context,
    onOpen: (SourceProvider) -> Unit,
): List<LinearLayout> = SourceRegistry.all().map { sourcePickerRow(context, it) { onOpen(it) } }

/**
 * A large, tappable source row: a leading globe icon inside a tinted disc (echoing the empty-state
 * and `makeSourceChip` accents), a name + host column, and a trailing "Open" affordance with a
 * forward arrow so the row reads as a navigation entry, not a static label. Whole-row ripple so the
 * tap target is the full card.
 */
private fun sourcePickerRow(
    context: android.content.Context,
    provider: SourceProvider,
    onOpen: () -> Unit,
): LinearLayout {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.cardRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(
            context.dp(Space.LG),
            context.dp(Space.MD),
            context.dp(Space.MD),
            context.dp(Space.MD),
        )
        background = selectableRipple(colors.elevation1)
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.MD)
            }
        // Leading icon disc — primaryContainer/onPrimaryContainer keeps each theme's accent readable.
        val discSize = context.dp(44)
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                background = roundedBg(colors.primaryContainer, discSize / 2f)
                addView(
                    ImageView(context).apply {
                        contentDescription = provider.name
                        setImageDrawable(context.tintedIcon(R.drawable.wna_globe, colors.onPrimaryContainer))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    },
                    LinearLayout.LayoutParams(context.dp(22), context.dp(22)),
                )
            },
            LinearLayout.LayoutParams(discSize, discSize),
        )
        // Name + host column. Host is derived from the baseUrl so adding a source needs no new field.
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = provider.name
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                        typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(colors.onSurface)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    },
                )
                val host = SourcePickerPlanning.host(provider.baseUrl)
                if (host.isNotEmpty()) {
                    addView(
                        TextView(context).apply {
                            text = host
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                            setTextColor(colors.onSurfaceVariant)
                            setPadding(0, context.dp(2), 0, 0)
                            maxLines = 1
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
        // Trailing "Open" affordance — label + forward arrow — so the row reads as navigational.
        addView(
            TextView(context).apply {
                text = "Open"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.LABEL_LARGE.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(colors.primary)
                letterSpacing = 0.04f
                includeFontPadding = false
            },
        )
        addView(
            ImageView(context).apply {
                contentDescription = "Open ${provider.name}"
                setImageDrawable(context.tintedIcon(R.drawable.wna_arrow_forward, colors.primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                marginStart = context.dp(Space.XS)
            },
        )
        setOnClickListener { onOpen() }
    }
}
