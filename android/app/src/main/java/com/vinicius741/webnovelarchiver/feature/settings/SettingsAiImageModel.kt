package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.OpenRouterImageModel
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
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
 * Cover-image model picker for Settings → AI Settings. Mirrors the description-model picker
 * (search + capped list + pinned manual entry) but rides the dedicated image-model catalog
 * (`GET /api/v1/images/models`), which ships no pricing — so there are no price labels and no
 * Free filter here, and each row notes the request parameters the model supports.
 */

/** Process-lifetime image-model-catalog cache so the picker reopens instantly after the first fetch. */
@Volatile
private var imageModelCatalogCache: List<OpenRouterImageModel>? = null

/** Opens the image model picker, fetching OpenRouter's image catalog on first use. */
internal fun ScreenHost.showAiImageModelPicker(
    currentModel: String,
    onPicked: (String) -> Unit,
) {
    val cached = imageModelCatalogCache
    if (cached != null) {
        showAiImageModelDialog(cached, currentModel, onPicked)
        return
    }
    toast("Loading OpenRouter image models...")
    scope.launch {
        val result = runCatching { app.appContainer.openRouter.fetchImageModels() }
        val models = result.getOrNull()
        if (models == null) {
            // A failed fetch must not open an empty picker — surface the reason and leave the
            // cache empty so the next open retries (nothing is cached on failure).
            toast(result.exceptionOrNull()?.message ?: "Could not load image models")
            return@launch
        }
        imageModelCatalogCache = models
        app.runOnUiThread { showAiImageModelDialog(models, currentModel, onPicked) }
    }
}

/** Bounded, searchable image model picker; manual entry stays pinned for ids missing from the catalog. */
private fun ScreenHost.showAiImageModelDialog(
    models: List<OpenRouterImageModel>,
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
    dialogView.addView(makeText(app, "AI Image Model", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(app, "Search by name or id", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.XS), 0, app.dp(Space.MD))
        },
    )
    val search = makeSearchField(app, "Search image models")
    dialogView.addView(search)

    val resultCount =
        makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.MD), 0, app.dp(Space.SM))
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
        val query =
            search.text
                .toString()
                .trim()
                .lowercase()
        val filtered =
            models.filter { model ->
                query.isEmpty() || model.id.lowercase().contains(query) || model.name.lowercase().contains(query)
            }
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "model" else "models"}"
        results.removeAllViews()
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
                        "Use any OpenRouter image model id, e.g. a new release missing from the list",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply { setPadding(0, app.dp(Space.XS), 0, 0) },
                )
                setOnClickListener {
                    dialogRef?.dismiss()
                    showImageManualModelDialog(selectedId, onPicked)
                }
            },
        )
        results.addView(makeDivider(app))
        filtered.take(MAX_RENDERED_IMAGE_MODELS).forEach { model ->
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
                        makeText(app, model.id, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                            setPadding(0, app.dp(Space.XS), 0, 0)
                        },
                    )
                    setOnClickListener {
                        dialogRef?.dismiss()
                        onPicked(model.id)
                    }
                },
            )
        }
        if (filtered.size > MAX_RENDERED_IMAGE_MODELS) {
            results.addView(
                makeText(
                    app,
                    "...and ${filtered.size - MAX_RENDERED_IMAGE_MODELS} more — refine your search",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM)) },
            )
        } else if (filtered.isEmpty()) {
            results.addView(
                makeText(app, "No image models match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant)
                    .apply { setPadding(0, app.dp(Space.LG), 0, app.dp(Space.LG)) },
            )
        }
    }

    search.doAfterTextChanged { renderResults() }
    renderResults()

    dialogView.addView(
        LinearLayout(app).apply {
            gravity = android.view.Gravity.END
            setPadding(0, app.dp(Space.MD), 0, 0)
            addView(makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() })
        },
    )
    dialogRef = AlertDialog.Builder(app).setView(dialogView).create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

/** Plain text-input dialog for image model ids that are not (or not yet) in the catalog. */
private fun ScreenHost.showImageManualModelDialog(
    currentModel: String,
    onPicked: (String) -> Unit,
) = prompt("Image model id (e.g. x-ai/grok-imagine-image-2.0)", currentModel) { value ->
    value.trim().takeIf { it.isNotBlank() }?.let(onPicked)
}

/** Render cap for the dialog list; scrolling many rows on a phone dialog gets sluggish. */
private const val MAX_RENDERED_IMAGE_MODELS = 80
