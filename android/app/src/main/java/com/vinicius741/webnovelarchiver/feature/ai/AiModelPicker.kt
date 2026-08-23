package com.vinicius741.webnovelarchiver.feature.ai

import android.app.AlertDialog
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

/** Opens the searchable model picker, fetching OpenRouter's catalog on first use. */
internal fun ScreenHost.showAiModelPicker(
    currentModel: String,
    onPicked: (String) -> Unit,
) {
    val cached = modelCatalogCache
    if (cached != null) {
        showAiModelDialog(cached, currentModel, onPicked)
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
        app.runOnUiThread { showAiModelDialog(models, currentModel, onPicked) }
    }
}

/** Bounded, searchable model picker mirroring the TTS voice dialog (search + chips + scroll). */
private fun ScreenHost.showAiModelDialog(
    models: List<OpenRouterModel>,
    selectedId: String,
    onPicked: (String) -> Unit,
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
        val filtered = AiModelPresentation.filter(models, search.text.toString(), freeOnly)
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "model" else "models"}"
        results.removeAllViews()
        // Manual entry stays pinned as the first row so it is reachable even when the catalog
        // failed to load (offline) or nothing matches the search.
        results.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, app.dp(Space.MD), 0, app.dp(Space.MD))
                isClickable = true
                isFocusable = true
                background = selectableRipple(colors.onSurface)
                addView(makeText(app, "Enter model id manually…", Type.BODY_LARGE, colors.primary))
                addView(
                    makeText(
                        app,
                        "Use any OpenRouter model id, e.g. a new release missing from the list",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply { setPadding(0, app.dp(Space.XS), 0, 0) },
                )
                setOnClickListener {
                    dialogRef?.dismiss()
                    showManualModelDialog(selectedId, onPicked)
                }
            },
        )
        results.addView(makeDivider(app))
        filtered.take(MAX_RENDERED_MODELS).forEach { model ->
            results.addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, app.dp(Space.MD), 0, app.dp(Space.MD))
                    isClickable = true
                    isFocusable = true
                    background = selectableRipple(colors.onSurface)
                    addView(
                        makeText(
                            app,
                            if (model.id == selectedId) "✓  ${model.name}" else model.name,
                            Type.BODY_LARGE,
                            colors.onSurface,
                        ),
                    )
                    addView(
                        makeText(app, "${model.id} · ${AiModelPresentation.priceLabel(model)}", Type.BODY_SMALL, colors.onSurfaceVariant)
                            .apply { setPadding(0, app.dp(Space.XS), 0, 0) },
                    )
                    setOnClickListener {
                        dialogRef?.dismiss()
                        onPicked(model.id)
                    }
                },
            )
        }
        if (filtered.size > MAX_RENDERED_MODELS) {
            results.addView(
                makeText(
                    app,
                    "...and ${filtered.size - MAX_RENDERED_MODELS} more — refine your search",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM)) },
            )
        } else if (filtered.isEmpty()) {
            results.addView(
                makeText(app, "No models match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant)
                    .apply { setPadding(0, app.dp(Space.LG), 0, app.dp(Space.LG)) },
            )
        }
    }

    fun renderFilters() {
        filterRow.removeAllViews()
        filterRow.addView(
            makeChip(app, "All", !freeOnly) {
                freeOnly = false
                renderFilters()
                renderResults()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = app.dp(Space.SM)
            },
        )
        filterRow.addView(
            makeChip(app, "Free", freeOnly) {
                freeOnly = true
                renderFilters()
                renderResults()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
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

// Render cap for the dialog list; scrolling hundreds of rows on a phone dialog gets sluggish.
private const val MAX_RENDERED_MODELS = 80
