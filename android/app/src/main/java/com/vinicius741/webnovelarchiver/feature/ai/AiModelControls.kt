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
import com.vinicius741.webnovelarchiver.ai.AiModelPresentation
import com.vinicius741.webnovelarchiver.ai.OpenRouterModel
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Model-selection controls for the AI Controls screen. All four global model choices live in one
 * "Models" card at the top of the screen — they apply to every novel, so presenting them inline
 * with per-feature actions made them look per-novel. A pick is saved immediately into AiSettings
 * and mirrored into the field label; manual model-id entry stays reachable from inside each picker.
 */

/**
 * The single Models card: description, cover image, rewrite, and verifier selectors. The verifier
 * must differ from the rewrite model — each picker hides the other row's current model and the
 * save path rejects a manual-entry collision, so no explanatory prose is needed.
 */
internal fun ScreenHost.addAiModelsCard(
    container: LinearLayout,
    story: Story,
) {
    val cardView =
        container.card {
            addAiModelRow(
                container = this,
                label = "Description model",
                currentModel = { repository.getAiSettings().descriptionModel },
            ) { picked ->
                repository.saveAiSettings(repository.getAiSettings().copy(descriptionModel = picked))
            }
            spacer(Space.SM)
            addAiModelRow(
                container = this,
                label = "Cover image model",
                currentModel = { repository.getAiSettings().imageModel },
                image = true,
            ) { picked ->
                repository.saveAiSettings(repository.getAiSettings().copy(imageModel = picked))
            }
            spacer(Space.SM)
            addAiModelRow(
                container = this,
                label = "Rewrite model",
                currentModel = { repository.getAiSettings().chapterRewriteModel },
                recommended = { AiModelPresentation.isKnownGoodRewriteModel(it.id) },
                excluded = { repository.getAiSettings().chapterVerifierModel },
            ) { picked ->
                if (picked == repository.getAiSettings().chapterVerifierModel) {
                    toast("The rewrite model must differ from the verifier")
                    if (frameIsAiControls(story.id)) showAiControls(story.id)
                    return@addAiModelRow
                }
                repository.saveAiSettings(repository.getAiSettings().copy(chapterRewriteModel = picked))
            }
            spacer(Space.SM)
            addAiModelRow(
                container = this,
                label = "Verifier model",
                currentModel = { repository.getAiSettings().chapterVerifierModel },
                excluded = { repository.getAiSettings().chapterRewriteModel },
            ) { picked ->
                if (picked == repository.getAiSettings().chapterRewriteModel) {
                    toast("The verifier must differ from the rewrite model")
                    if (frameIsAiControls(story.id)) showAiControls(story.id)
                    return@addAiModelRow
                }
                repository.saveAiSettings(repository.getAiSettings().copy(chapterVerifierModel = picked))
            }
        }
    container.addView(cardView)
    container.text(
        "Models apply to every novel; the API key lives in Settings → AI.",
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
}

private fun ScreenHost.addAiModelRow(
    container: LinearLayout,
    label: String,
    currentModel: () -> String,
    image: Boolean = false,
    recommended: ((OpenRouterModel) -> Boolean)? = null,
    excluded: () -> String? = { null },
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
                showAiModelPicker(currentModel(), { picked -> pick?.invoke(picked) }, recommended, excluded())
            }
        }
    selectorView.layoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    container.addView(selectorView)

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
