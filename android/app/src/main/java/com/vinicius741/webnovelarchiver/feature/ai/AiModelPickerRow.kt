package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiModelPresentation
import com.vinicius741.webnovelarchiver.ai.OpenRouterModel
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * One catalog row: display name, id + price. The drafted pick is highlighted with
 * a trailing check and, when it supports reasoning, expands in place with its [reasoning] control.
 * [model] is null for a manual id that the catalog doesn't know.
 */
internal fun modelResultRow(
    context: Context,
    id: String,
    model: OpenRouterModel?,
    selected: Boolean,
    reasoning: View?,
    onPick: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val radiusPx = context.dp(ThemeManager.shapes.buttonRadius).toFloat()
    val textColumn =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(context, model?.name ?: id, Type.BODY_LARGE, colors.onSurface).apply {
                    if (selected) typeface = Typeface.create(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            if (model != null) {
                addView(
                    makeText(
                        context,
                        "${model.id} · ${AiModelPresentation.priceLabel(model)}",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply {
                        setPadding(0, context.dp(2), 0, 0)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                )
            }
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isSelected = selected
        setPadding(context.dp(Space.MD), context.dp(10), context.dp(Space.MD), context.dp(10))
        background =
            ripple(
                roundedBg(if (selected) colors.surfaceVariant else Color.TRANSPARENT, radiusPx),
                radiusPx,
                colors.onSurface,
            )
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(1)
                bottomMargin = context.dp(1)
            }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) {
                    addView(
                        ImageView(context).apply {
                            setImageDrawable(context.tintedIcon(R.drawable.wna_check, colors.primary))
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            layoutParams =
                                LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
                                    marginStart = context.dp(Space.SM)
                                }
                        },
                    )
                }
            },
        )
        if (reasoning != null) {
            addView(
                reasoning,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.SM)
                },
            )
        }
        if (!selected) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onPick() }
        }
    }
}

/** Render cap for the model dialogs; scrolling hundreds of rows on a phone dialog gets sluggish. */
internal const val MAX_RENDERED_MODEL_ROWS = 80

/** The pinned "enter id manually" row: reachable even when the catalog is empty or filtered out. */
internal fun manualEntryRow(
    context: Context,
    subtitle: String? = null,
    onClick: () -> Unit,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD))
        isClickable = true
        isFocusable = true
        background = selectableRipple(ThemeManager.colors.onSurface)
        addView(makeText(context, "Enter model id manually…", Type.BODY_LARGE, ThemeManager.colors.primary))
        subtitle?.let {
            addView(
                makeText(context, it, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, context.dp(Space.XS), 0, 0)
                },
            )
        }
        setOnClickListener { onClick() }
    }

/** "N more — refine your search" tail, or the empty state when nothing matched. */
internal fun appendResultTail(
    context: Context,
    results: LinearLayout,
    matchCount: Int,
    emptyText: String = "No models match your search.",
) {
    val colors = ThemeManager.colors
    if (matchCount > MAX_RENDERED_MODEL_ROWS) {
        results.addView(
            makeText(
                context,
                "...and ${matchCount - MAX_RENDERED_MODEL_ROWS} more — refine your search",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM)) },
        )
    } else if (matchCount == 0) {
        results.addView(
            makeText(context, emptyText, Type.BODY_MEDIUM, colors.onSurfaceVariant)
                .apply { setPadding(0, context.dp(Space.LG), 0, context.dp(Space.LG)) },
        )
    }
}
