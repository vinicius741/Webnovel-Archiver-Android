package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Model-selection controls for the AI Controls screen (moved from AI Settings — the user changes
 * the model where generation happens). Each row presents an interactive selector field showing
 * the active model and opens the shared picker dialog; a pick is saved immediately into AiSettings
 * and mirrored into the field label. Manual model-id entry stays reachable from inside each picker.
 */

/** Description-section row: which text model writes synopses and image prompts. */
internal fun ScreenHost.addAiDescriptionModelRow(
    container: LinearLayout,
    story: Story,
) {
    addAiModelRow(
        container,
        label = "Description model",
        currentModel = { repository.getAiSettings().descriptionModel },
    ) { picked ->
        repository.saveAiSettings(repository.getAiSettings().copy(descriptionModel = picked))
        toast("Description model set to $picked")
        if (frameIsAiControls(story.id)) showAiControls(story.id)
    }
}

/** Cover-Art-section row: which image model paints covers. */
internal fun ScreenHost.addAiCoverModelRow(
    container: LinearLayout,
    story: Story,
) {
    addAiModelRow(
        container,
        label = "Cover image model",
        currentModel = { repository.getAiSettings().imageModel },
        image = true,
    ) { picked ->
        repository.saveAiSettings(repository.getAiSettings().copy(imageModel = picked))
        toast("Cover image model set to $picked")
        if (frameIsAiControls(story.id)) showAiControls(story.id)
    }
}

internal fun ScreenHost.addAiModelRow(
    container: LinearLayout,
    label: String,
    currentModel: () -> String,
    image: Boolean = false,
    onPicked: suspend (String) -> Unit,
) {
    var pick: ((String) -> Unit)? = null
    val (selectorView, valueView) =
        container.context.makeSelectorField(
            iconRes = R.drawable.wna_auto_awesome,
            label = label,
            value = currentModel(),
        ) {
            if (image) {
                showAiImageModelPicker(currentModel()) { picked -> pick?.invoke(picked) }
            } else {
                showAiModelPicker(currentModel()) { picked -> pick?.invoke(picked) }
            }
        }
    selectorView.layoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    container.addView(selectorView)

    val footnote =
        makeText(
            container.context,
            "Picking a model here saves it immediately for every novel — the API key stays in Settings.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            setPadding(container.context.dp(2), container.context.dp(Space.XS), container.context.dp(2), 0)
        }
    container.addView(footnote)

    pick = { picked ->
        valueView.text = picked
        scope.launch { onPicked(picked) }
    }
}

/**
 * Creates a reusable dropdown selector field with leading icon, label + value text column, and trailing chevron.
 */
internal fun Context.makeSelectorField(
    iconRes: Int,
    label: String,
    value: String,
    onClick: () -> Unit,
): Pair<LinearLayout, TextView> {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = dp(shapes.buttonRadius).toFloat()

    val leadingIcon =
        ImageView(this).apply {
            setImageDrawable(tintedIcon(iconRes, colors.primary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams =
                LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(Space.MD)
                }
        }

    val labelView =
        TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_SMALL.size())
            setTextColor(colors.onSurfaceVariant)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    val valueView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_MEDIUM.size())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(colors.onSurface)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
        }

    val textCol =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView)
            addView(valueView)
        }

    val chevronIcon =
        ImageView(this).apply {
            setImageDrawable(tintedIcon(R.drawable.wna_chevron_down, colors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams =
                LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginStart = dp(Space.SM)
                }
        }

    val selectorContainer =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(Space.MD), dp(Space.SM + 2), dp(Space.MD), dp(Space.SM + 2))
            background =
                ripple(
                    strokeBg(colors.surfaceVariant, radiusPx, colors.outlineVariant, dp(1)),
                    radiusPx,
                    colors.onSurface,
                )
            isClickable = true
            isFocusable = true
            addView(leadingIcon)
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevronIcon)
            setOnClickListener { onClick() }
        }

    return selectorContainer to valueView
}
