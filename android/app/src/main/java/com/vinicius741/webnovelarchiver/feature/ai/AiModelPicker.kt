package com.vinicius741.webnovelarchiver.feature.ai

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.ai.AiModelPresentation
import com.vinicius741.webnovelarchiver.ai.OpenRouterModel
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Description-model picker for the AI Controls screen (moved from AI Settings — the model is
 * changed where generation happens, not in global settings). Bounded, searchable catalog dialog
 * mirroring the TTS voice dialog (search + chips + scroll) with manual entry pinned first.
 */

/** Process-lifetime model-catalog cache so the picker reopens instantly after the first fetch. */
@Volatile
internal var modelCatalogCache: List<OpenRouterModel>? = null

/**
 * Opens the searchable model picker, fetching OpenRouter's catalog on first use. [recommended]
 * adds a "Known good" filter and row marks; [excluded] hides one id entirely (e.g. the verifier
 * picker hiding the model already chosen as the rewriter).
 */
internal fun ScreenHost.showAiModelPicker(
    currentModel: String,
    onPicked: (String) -> Unit,
    recommended: ((OpenRouterModel) -> Boolean)? = null,
    excluded: String? = null,
) {
    val cached = modelCatalogCache
    if (cached != null) {
        showAiModelDialog(cached, currentModel, onPicked, recommended, excluded)
        return
    }
    toast("Loading OpenRouter models...")
    scope.launch {
        val result = runCatching { app.appContainer.openRouter.fetchModels() }
        result.onFailure { toast(it.message ?: "Could not load models") }
        val models = result.getOrNull().orEmpty()
        // An empty successful catalog is cacheable, but a transient network/API failure must remain
        // retryable when the picker is reopened during the same process.
        result.getOrNull()?.let { modelCatalogCache = it }
        app.runOnUiThread { showAiModelDialog(models, currentModel, onPicked, recommended, excluded) }
    }
}

/** Bounded, searchable model picker mirroring the TTS voice dialog (search + chips + scroll). */
private fun ScreenHost.showAiModelDialog(
    models: List<OpenRouterModel>,
    selectedId: String,
    onPicked: (String) -> Unit,
    recommended: ((OpenRouterModel) -> Boolean)?,
    excluded: String?,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, app.dp(shapes.dialogRadius).toFloat())
            roundCorners(shapes.dialogRadius.toFloat())
        }
    dialogView.addView(makeText(app, "AI Model", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(app, "Search by name or id", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.XS), 0, app.dp(Space.MD))
        },
    )
    val search = makeSearchField(app, "Search models")
    dialogView.addView(search)

    var freeOnly = false
    var knownGoodOnly = false
    val filterRow = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    dialogView.addView(
        android.widget.HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, app.dp(Space.MD), 0, app.dp(Space.SM))
            addView(filterRow)
        },
    )

    val resultCount =
        makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            setPadding(0, 0, 0, app.dp(Space.SM))
        }
    dialogView.addView(resultCount)
    val results = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val maxResultsHeight = minOf(app.dp(380), (app.resources.displayMetrics.heightPixels * 0.48f).toInt())
    dialogView.addView(
        ScrollView(app).apply {
            isFillViewport = true
            addView(results)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxResultsHeight),
    )

    var dialogRef: AlertDialog? = null

    fun renderResults() {
        val filtered =
            AiModelPresentation
                .filter(models, search.text.toString(), freeOnly)
                .filter { it.id != excluded }
                .filter { knownGoodOnly != true || recommended?.invoke(it) == true }
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "model" else "models"}"
        results.removeAllViews()
        // Manual entry stays pinned as the first row so it is reachable even when the catalog
        // failed to load (offline) or nothing matches the search.
        results.addView(
            manualEntryRow(app) {
                dialogRef?.dismiss()
                showManualModelDialog(selectedId, onPicked)
            },
        )
        results.addView(makeDivider(app))
        filtered.take(MAX_RENDERED_MODELS).forEach { model ->
            results.addView(
                modelResultRow(app, model, selectedId, recommended) {
                    dialogRef?.dismiss()
                    onPicked(model.id)
                },
            )
        }
        appendResultTail(app, results, filtered.size)
    }

    fun renderFilters() {
        filterRow.removeAllViews()

        fun chip(
            label: String,
            selected: Boolean,
            onPick: () -> Unit,
        ) {
            filterRow.addView(
                makeChip(app, label, selected) {
                    onPick()
                    renderFilters()
                    renderResults()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = app.dp(Space.SM)
                },
            )
        }
        chip("All", !freeOnly && !knownGoodOnly) {
            freeOnly = false
            knownGoodOnly = false
        }
        chip("Free", freeOnly) {
            freeOnly = true
            knownGoodOnly = false
        }
        if (recommended != null) {
            chip("Known good", knownGoodOnly) {
                knownGoodOnly = true
                freeOnly = false
            }
        }
    }

    search.doAfterTextChanged { renderResults() }
    renderFilters()
    renderResults()

    dialogView.addView(
        LinearLayout(app).apply {
            gravity = Gravity.END
            setPadding(0, app.dp(Space.MD), 0, 0)
            addView(makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() })
        },
    )
    dialogRef = AlertDialog.Builder(app).setView(dialogView).create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

/** Plain text-input dialog for model ids that are not (or not yet) in the catalog. */
internal fun ScreenHost.showManualModelDialog(
    currentModel: String,
    onPicked: (String) -> Unit,
) = prompt("Model id (e.g. deepseek/deepseek-v4-flash-0731)", currentModel) { value ->
    value.trim().takeIf { it.isNotBlank() }?.let(onPicked)
}

/** The pinned "enter id manually" row: reachable even when the catalog is empty or filtered out. */
private fun manualEntryRow(
    context: Context,
    onClick: () -> Unit,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(Space.MD), 0, context.dp(Space.MD))
        isClickable = true
        isFocusable = true
        background = selectableRipple(ThemeManager.colors.onSurface)
        addView(makeText(context, "Enter model id manually…", Type.BODY_LARGE, ThemeManager.colors.primary))
        addView(
            makeText(
                context,
                "Use any OpenRouter model id, e.g. a new release missing from the list",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            ).apply { setPadding(0, context.dp(Space.XS), 0, 0) },
        )
        setOnClickListener { onClick() }
    }

/** One catalog row: display name, id + price (+"known good" for spike-validated rewriters). */
private fun modelResultRow(
    context: Context,
    model: OpenRouterModel,
    selectedId: String,
    recommended: ((OpenRouterModel) -> Boolean)?,
    onPick: () -> Unit,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(Space.MD), 0, context.dp(Space.MD))
        isClickable = true
        isFocusable = true
        background = selectableRipple(ThemeManager.colors.onSurface)
        val name = if (model.id == selectedId) "✓  ${model.name}" else model.name
        addView(makeText(context, name, Type.BODY_LARGE, ThemeManager.colors.onSurface))
        val knownGood = if (recommended?.invoke(model) == true) " · known good" else ""
        addView(
            makeText(
                context,
                "${model.id} · ${AiModelPresentation.priceLabel(model)}$knownGood",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            ).apply { setPadding(0, context.dp(Space.XS), 0, 0) },
        )
        setOnClickListener { onPick() }
    }

/** "N more — refine your search" tail, or the empty state when nothing matched. */
private fun appendResultTail(
    context: Context,
    results: LinearLayout,
    matchCount: Int,
) {
    val colors = ThemeManager.colors
    if (matchCount > MAX_RENDERED_MODELS) {
        results.addView(
            makeText(
                context,
                "...and ${matchCount - MAX_RENDERED_MODELS} more — refine your search",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM)) },
        )
    } else if (matchCount == 0) {
        results.addView(
            makeText(context, "No models match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant)
                .apply { setPadding(0, context.dp(Space.LG), 0, context.dp(Space.LG)) },
        )
    }
}

// Render cap for the dialog list; scrolling hundreds of rows on a phone dialog gets sluggish.
private const val MAX_RENDERED_MODELS = 80
