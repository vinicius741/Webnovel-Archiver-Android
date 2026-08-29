package com.vinicius741.webnovelarchiver.feature.ai

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Context-chapter selection for the AI Controls screen. Generation sends the first downloaded
 * chapters by default (AiDescriptionPlanning.selectContextChapters); this row lets the user pick
 * exactly which downloaded chapters are sent instead. The selection persists per story on
 * Story.aiContextChapterIndices, so it also reaches the process-wide cover-job coordinator.
 */

/** Context-chapter selector row: which downloaded chapters are sent to OpenRouter. */
internal fun ScreenHost.addAiContextChaptersRow(
    container: LinearLayout,
    story: Story,
) {
    var valueView: TextView? = null
    val (selectorView, textVal) =
        container.context.makeSelectorField(
            iconRes = com.vinicius741.webnovelarchiver.R.drawable.wna_menu_book,
            label = "Chapters sent to AI",
            value = AiDescriptionPlanning.contextChaptersLabel(story),
        ) {
            showAiContextChapterDialog(story) { saved ->
                valueView?.text = AiDescriptionPlanning.contextChaptersLabel(saved)
                // Re-render so the captured story (and this dialog's next open) sees the
                // saved selection instead of the pre-save snapshot.
                if (frameIsAiControls(story.id)) showAiControls(story.id)
            }
        }
    valueView = textVal
    selectorView.layoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    container.addView(selectorView)
}

/** Multi-select dialog over the story's downloaded chapters, with a Reset-to-default action. */
private fun ScreenHost.showAiContextChapterDialog(
    story: Story,
    onChanged: (Story) -> Unit,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val downloaded = story.chapters.withIndex().filter { it.value.downloaded }
    val defaults = AiDescriptionPlanning.selectContextChapters(story).toSet()
    val saved = story.aiContextChapterIndices?.toSet()
    // With no explicit selection the dialog opens pre-checked with the default chapters, so
    // saving unchanged keeps the default (persisted as null) rather than freezing it explicitly.
    val checked = (saved ?: defaults).toMutableSet()

    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, app.dp(shapes.dialogRadius).toFloat())
            roundCorners(shapes.dialogRadius.toFloat())
        }
    dialogView.addView(makeText(app, "Chapters sent to AI", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(app, "Only downloaded chapters can be sent.", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.XS), 0, app.dp(Space.MD))
        },
    )
    val search = makeSearchField(app, "Search chapters")
    dialogView.addView(search)
    val resultCount =
        makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.MD), 0, app.dp(Space.SM))
        }
    dialogView.addView(resultCount)
    val list = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val maxListHeight = minOf(app.dp(380), (app.resources.displayMetrics.heightPixels * 0.48f).toInt())
    dialogView.addView(
        ScrollView(app).apply {
            isFillViewport = true
            addView(list)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight),
    )

    var dialogRef: AlertDialog? = null

    // Long novels can list over a thousand downloaded chapters; render only search matches (capped)
    // so the dialog never inflates one subtree per chapter on the main thread.
    fun renderRows() {
        val query = search.text.toString().trim()
        val filtered =
            downloaded.filter { (index, chapter) ->
                query.isEmpty() || "chapter ${index + 1} ${chapter.title}".contains(query, ignoreCase = true)
            }
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "chapter" else "chapters"}"
        list.removeAllViews()
        filtered.take(MAX_RENDERED_CHAPTERS).forEach { (index, chapter) ->
            val checkBox =
                CheckBox(app).apply {
                    text = ""
                    isChecked = index in checked
                }
            styledCheckBox(checkBox)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(index) else checked.remove(index)
            }
            list.addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM))
                    isClickable = true
                    addView(
                        checkBox,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        LinearLayout(app).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(makeText(app, "Chapter ${index + 1}", Type.BODY_LARGE, colors.onSurface))
                            chapter.title.takeIf { it.isNotBlank() }?.let { title ->
                                addView(
                                    makeText(app, title, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                                        setPadding(0, app.dp(Space.XS), 0, 0)
                                    },
                                )
                            }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    setOnClickListener { checkBox.toggle() }
                },
            )
        }
        if (filtered.size > MAX_RENDERED_CHAPTERS) {
            list.addView(
                makeText(
                    app,
                    "...and ${filtered.size - MAX_RENDERED_CHAPTERS} more — refine your search",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM)) },
            )
        } else if (filtered.isEmpty()) {
            list.addView(
                makeText(app, "No chapters match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant)
                    .apply { setPadding(0, app.dp(Space.LG), 0, app.dp(Space.LG)) },
            )
        }
    }

    search.doAfterTextChanged { renderRows() }
    renderRows()

    fun applySelection(indices: List<Int>?) {
        scope.launch {
            val saved = repository.setAiContextChapters(story.id, indices)
            if (saved != null) {
                toast("Chapters sent to AI updated")
                onChanged(saved)
            }
        }
        dialogRef?.dismiss()
    }

    dialogView.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, app.dp(Space.MD), 0, 0)
            addView(
                makeButton(app, "Reset to default (first downloaded)", Btn.TEXT, 0) {
                    applySelection(null)
                }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START },
            )
            row {
                addView(
                    makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    makeButton(app, "Save", Btn.FILLED) {
                        // An empty selection is not a saveable state: resolveContextChapters
                        // would silently fall back to the default chapters, doing the opposite
                        // of the user's "send none" intent.
                        if (checked.isEmpty()) {
                            toast("Select at least one chapter, or use Reset to default")
                            return@makeButton
                        }
                        // Saving a selection identical to the default keeps the default semantics
                        // (null) so future CONTEXT_CHAPTER_COUNT changes still apply.
                        applySelection(checked.takeIf { it != defaults }?.sorted())
                    },
                )
            }
        },
    )
    dialogRef = AlertDialog.Builder(app).setView(dialogView).create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

// Render cap for the dialog list; scrolling hundreds of rows on a phone dialog gets sluggish.
private const val MAX_RENDERED_CHAPTERS = 80
