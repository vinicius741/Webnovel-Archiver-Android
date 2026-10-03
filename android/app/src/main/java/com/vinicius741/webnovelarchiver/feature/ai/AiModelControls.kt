package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiModelPresentation
import com.vinicius741.webnovelarchiver.ai.AiModelSelection
import com.vinicius741.webnovelarchiver.ai.AiReasoningPlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterModel
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
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
 * with per-feature actions made them look per-novel. Confirmed text-model and reasoning choices
 * are saved together into AiSettings and mirrored inside the model field. Manual ids remain reachable.
 */

/**
 * The single Models card: description, cover image, rewrite, and verifier selectors. The verifier
 * must differ from the rewrite model — each picker hides the other row's current model and the
 * save path rejects a manual-entry collision, so no explanatory prose is needed.
 */
internal fun ScreenHost.addAiModelsCard(container: LinearLayout) {
    val modelRefreshers = mutableListOf<() -> Unit>()
    aiControlsScreenState.binding?.refreshModels = { modelRefreshers.forEach { it() } }
    val cardView =
        container.card {
            modelRefreshers +=
                addAiModelRow(
                    container = this,
                    label = "Description model",
                    currentModel = { repository.getAiSettings().descriptionModel },
                ) { picked ->
                    val settings = repository.getAiSettings()
                    repository.saveAiSettings(
                        settings.copy(
                            descriptionModel = picked.modelId,
                            reasoningEfforts = settings.reasoningEfforts + (picked.modelId to requireNotNull(picked.reasoningEffort)),
                        ),
                    )
                    refreshAiModelFields()
                }
            spacer(Space.SM)
            modelRefreshers +=
                addAiModelRow(
                    container = this,
                    label = "Cover image model",
                    currentModel = { repository.getAiSettings().imageModel },
                    image = true,
                ) { picked ->
                    repository.saveAiSettings(repository.getAiSettings().copy(imageModel = picked.modelId))
                    refreshAiModelFields()
                }
            spacer(Space.SM)
            modelRefreshers +=
                addAiModelRow(
                    container = this,
                    label = "Rewrite model",
                    currentModel = { repository.getAiSettings().chapterRewriteModel },
                    recommended = { AiModelPresentation.isKnownGoodRewriteModel(it.id) },
                    excluded = { repository.getAiSettings().chapterVerifierModel },
                ) { picked ->
                    if (picked.modelId == repository.getAiSettings().chapterVerifierModel) {
                        toast("The rewrite model must differ from the verifier")
                        return@addAiModelRow
                    }
                    val settings = repository.getAiSettings()
                    repository.saveAiSettings(
                        settings.copy(
                            chapterRewriteModel = picked.modelId,
                            reasoningEfforts = settings.reasoningEfforts + (picked.modelId to requireNotNull(picked.reasoningEffort)),
                        ),
                    )
                    refreshAiModelFields()
                }
            spacer(Space.SM)
            modelRefreshers +=
                addAiModelRow(
                    container = this,
                    label = "Verifier model",
                    currentModel = { repository.getAiSettings().chapterVerifierModel },
                    excluded = { repository.getAiSettings().chapterRewriteModel },
                ) { picked ->
                    if (picked.modelId == repository.getAiSettings().chapterRewriteModel) {
                        toast("The verifier must differ from the rewrite model")
                        return@addAiModelRow
                    }
                    val settings = repository.getAiSettings()
                    repository.saveAiSettings(
                        settings.copy(
                            chapterVerifierModel = picked.modelId,
                            reasoningEfforts = settings.reasoningEfforts + (picked.modelId to requireNotNull(picked.reasoningEffort)),
                        ),
                    )
                    refreshAiModelFields()
                }
        }
    container.addView(cardView)
    container.text(
        "Models apply to every novel; the API key lives in Settings → AI.",
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
    scope.launch {
        loadAiModelCatalog()
        refreshAiModelFields()
    }
}

private fun ScreenHost.addAiModelRow(
    container: LinearLayout,
    label: String,
    currentModel: () -> String,
    image: Boolean = false,
    recommended: ((OpenRouterModel) -> Boolean)? = null,
    excluded: () -> String? = { null },
    onPicked: suspend (AiModelSelection) -> Unit,
): () -> Unit {
    var pick: ((AiModelSelection) -> Unit)? = null
    var refresh: () -> Unit = {}
    val (selectorView, valueView, detailView) =
        container.context.makeSelectorField(
            iconRes = R.drawable.wna_auto_awesome,
            label = label,
            value = currentModel(),
            trailingBadge = true,
        ) {
            if (image) {
                showAiImageModelPicker(currentModel()) { picked -> pick?.invoke(AiModelSelection(picked)) }
            } else {
                showAiModelPicker(currentModel(), { picked -> pick?.invoke(picked) }, recommended, excluded())
            }
        }
    selectorView.layoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    container.addView(selectorView)

    pick = { picked ->
        scope.launch {
            onPicked(picked)
            refresh()
        }
    }
    refresh = {
        val modelId = currentModel()
        valueView.text = modelId
        val model = modelCatalogCache?.firstOrNull { it.id == modelId }
        detailView.visibility = if (!image && AiReasoningPlanning.allowedEfforts(model).isNotEmpty()) View.VISIBLE else View.GONE
        detailView.text =
            "Reasoning: " + AiReasoningEffort.shortLabel(AiReasoningPlanning.effortFor(repository.getAiSettings(), modelId, model))
    }
    refresh()
    return refresh
}

/**
 * Creates a reusable dropdown selector field with leading icon, label + value text column, and trailing chevron.
 * The returned detail view is a third line under the value, or — with [trailingBadge] — a compact pill
 * beside the chevron that keeps every row two lines tall.
 */
internal fun Context.makeSelectorField(
    iconRes: Int,
    label: String,
    value: String,
    trailingBadge: Boolean = false,
    onClick: () -> Unit,
): Triple<LinearLayout, TextView, TextView> {
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

    val detailView =
        makeText(this, "", if (trailingBadge) Type.LABEL_SMALL else Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            visibility = View.GONE
            if (trailingBadge) {
                setTextColor(colors.onSecondaryContainer)
                maxLines = 1
                setPadding(dp(Space.SM), dp(3), dp(Space.SM), dp(3))
                background = roundedBg(colors.secondaryContainer, dp(shapes.chipRadius).toFloat())
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(Space.SM)
                    }
            } else {
                setPadding(0, dp(Space.XS), 0, 0)
            }
        }
    val textCol =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView)
            addView(valueView)
            if (!trailingBadge) addView(detailView)
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
            if (trailingBadge) addView(detailView)
            addView(chevronIcon)
            setOnClickListener { onClick() }
        }

    return Triple(selectorContainer, valueView, detailView)
}
