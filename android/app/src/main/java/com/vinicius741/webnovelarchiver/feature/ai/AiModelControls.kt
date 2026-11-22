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
import com.vinicius741.webnovelarchiver.ai.AiModelSelection
import com.vinicius741.webnovelarchiver.ai.AiReasoningPlanning
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort
import com.vinicius741.webnovelarchiver.feature.settings.AiSettingsScreenModel
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Model-selection controls for AI Settings. The global model choices live there (the chapter
 * selection model under Advanced) — they apply to every novel, so the per-novel AI Controls screen only names the model
 * each tab uses and links here. Confirmed text-model and reasoning choices are saved together into
 * AiSettings and mirrored inside the model field. Manual ids remain reachable.
 */

/**
 * The Models card (description and cover image) in [container], and the chapter selection
 * (decision) model in its own card in [advancedContainer].
 */
internal fun ScreenHost.addAiModelsCard(
    container: LinearLayout,
    advancedContainer: LinearLayout,
    model: AiSettingsScreenModel,
) {
    val modelRefreshers = mutableListOf<() -> Unit>()
    // Picker callbacks can outlive a fold-triggered render, so they poke the model, and whichever
    // render of these rows is attached then refreshes.
    container.bindWhileAttached(scope, model.state.map { it.modelsRevision }.distinctUntilChanged()) { modelRefreshers.forEach { it() } }
    val settings = { model.state.value.settings }

    /** One row per global model choice; [save] applies the picked id (+ reasoning effort) to settings. */
    class ModelRow(
        val label: String,
        val current: () -> String,
        val save: (AiSettings, String, String?) -> AiSettings,
        val kind: AiModelRowKind = AiModelRowKind.TEXT,
    )

    val rows =
        listOf(
            ModelRow(
                label = "Description model",
                current = { settings().descriptionModel },
                save = { settings, id, effort ->
                    settings.copy(
                        descriptionModel = id,
                        reasoningEfforts =
                            settings.reasoningEfforts + (id to requireNotNull(effort)),
                    )
                },
            ),
            ModelRow(
                label = "Cover image model",
                current = { settings().imageModel },
                save = { settings, id, _ -> settings.copy(imageModel = id) },
                kind = AiModelRowKind.IMAGE,
            ),
            ModelRow(
                label = "Chapter selection model",
                current = { settings().decisionModel },
                save = { settings, id, _ -> settings.copy(decisionModel = id) },
                kind = AiModelRowKind.DECISIONS,
            ),
        )

    fun LinearLayout.addRows(shown: List<ModelRow>) =
        shown.forEachIndexed { index, row ->
            if (index > 0) spacer(Space.SM)
            modelRefreshers +=
                addAiModelRow(
                    container = this,
                    label = row.label,
                    currentModel = row.current,
                    settings = settings,
                    kind = row.kind,
                ) { picked -> model.saveModelChoice { row.save(it, picked.modelId, picked.reasoningEffort) } }
        }
    val (advancedRows, mainRows) = rows.partition { it.kind == AiModelRowKind.DECISIONS }
    container.addView(container.card { addRows(mainRows) })
    advancedContainer.addView(advancedContainer.card { addRows(advancedRows) })
    scope.launch {
        loadAiModelCatalog()
        model.modelsChanged()
    }
}

internal enum class AiModelRowKind { TEXT, IMAGE, DECISIONS }

/** Refreshes the model rows of the AI Settings render on screen, which a fold may have replaced while a picker stayed open. */
internal fun ScreenHost.refreshAiModelFields() {
    screenModels.find<AiSettingsScreenModel>(AppRoute.AiSettings.stableKey)?.modelsChanged()
}

private fun ScreenHost.addAiModelRow(
    container: LinearLayout,
    label: String,
    currentModel: () -> String,
    settings: () -> AiSettings,
    kind: AiModelRowKind = AiModelRowKind.TEXT,
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
            when (kind) {
                AiModelRowKind.IMAGE -> showAiImageModelPicker(currentModel()) { picked -> pick?.invoke(AiModelSelection(picked)) }
                AiModelRowKind.DECISIONS ->
                    showAiModelPicker(currentModel(), { picked -> pick?.invoke(picked) }, catalog = AiModelCatalog.DECISIONS)
                AiModelRowKind.TEXT -> showAiModelPicker(currentModel(), { picked -> pick?.invoke(picked) })
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
        detailView.visibility =
            if (kind == AiModelRowKind.TEXT && AiReasoningPlanning.allowedEfforts(model).isNotEmpty()) View.VISIBLE else View.GONE
        detailView.text =
            "Reasoning: " + AiReasoningEffort.shortLabel(AiReasoningPlanning.effortFor(settings(), modelId, model))
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
