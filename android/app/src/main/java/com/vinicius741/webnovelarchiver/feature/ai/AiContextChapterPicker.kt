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
import com.vinicius741.webnovelarchiver.ai.AiCoverContextPlanning
import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.setAiCoverContextChapters
import com.vinicius741.webnovelarchiver.data.storage.EvidenceSelectionKind
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

// Independent persisted chapter selectors for descriptions and covers.

/** Context-chapter selector row: which downloaded chapters are sent to OpenRouter. */
internal fun ScreenHost.addAiContextChaptersRow(
    container: LinearLayout,
    story: Story,
    forCover: Boolean = false,
) {
    var valueView: TextView? = null
    val automaticSelection = automaticSelection(story, forCover)
    val (selectorView, textVal) =
        container.context.makeSelectorField(
            iconRes = com.vinicius741.webnovelarchiver.R.drawable.wna_menu_book,
            label = if (forCover) "Cover context chapters" else "Description context chapters",
            value =
                if (forCover) {
                    AiCoverContextPlanning.contextChaptersLabel(story, automaticSelection)
                } else {
                    AiDescriptionPlanning.contextChaptersLabel(story, automaticSelection)
                },
        ) {
            showAiContextChapterDialog(story, forCover) { saved ->
                valueView?.text =
                    if (forCover) {
                        AiCoverContextPlanning.contextChaptersLabel(saved, automaticSelection)
                    } else {
                        AiDescriptionPlanning.contextChaptersLabel(saved, automaticSelection)
                    }
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

/** Chapters the decision model kept on the story's last automatic scan for this generator, if any. */
private fun ScreenHost.automaticSelection(
    story: Story,
    forCover: Boolean,
): List<Int>? =
    app.appContainer.chapterEvidence.selection(
        story.id,
        if (forCover) EvidenceSelectionKind.COVER else EvidenceSelectionKind.DESCRIPTION,
    )

/** Multi-select dialog over the story's downloaded chapters, with a Reset-to-automatic action. */
private fun ScreenHost.showAiContextChapterDialog(
    story: Story,
    forCover: Boolean,
    onChanged: (Story) -> Unit,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val downloaded = story.chapters.withIndex().filter { it.value.downloaded }
    val config = contextPickerConfig(story, forCover)
    val checked = config.checked

    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, app.dp(shapes.dialogRadius).toFloat())
            roundCorners(shapes.dialogRadius.toFloat())
        }
    dialogView.addView(makeText(app, "Chapters sent to AI", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(
            app,
            config.hint,
            Type.BODY_SMALL,
            colors.onSurfaceVariant,
        ).apply {
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
                matchesChapterQuery(index, chapter.title, query)
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
            val saved = config.save(indices)
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
                makeButton(app, config.resetLabel, Btn.TEXT, 0) {
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
                        // An empty selection is not a saveable state: it would silently mean
                        // automatic selection, the opposite of the user's "send none" intent.
                        if (checked.isEmpty()) {
                            toast("Select at least one chapter, or use Reset to automatic selection")
                            return@makeButton
                        }
                        // An explicit selection always bypasses the decision-model scan, even when it
                        // matches the last automatic pick.
                        applySelection(checked.sorted())
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

private data class ContextPickerConfig(
    val checked: MutableSet<Int>,
    val hint: String,
    val resetLabel: String,
    val save: suspend (List<Int>?) -> Story?,
)

private fun ScreenHost.contextPickerConfig(
    story: Story,
    forCover: Boolean,
): ContextPickerConfig {
    val saved = (if (forCover) story.aiCoverContextChapterIndices else story.aiContextChapterIndices)?.takeIf { it.isNotEmpty() }
    val automatic = automaticSelection(story, forCover).orEmpty()
    // Automatic mode has no persisted manual selection — the picker shows the chapters the
    // decision model kept on the last scan, or nothing before the first scan ever runs.
    return ContextPickerConfig(
        checked = (saved ?: automatic).toMutableSet(),
        hint =
            when {
                saved == null && automatic.isNotEmpty() ->
                    "The last automatic scan picked these chapters. " +
                        "Adjust them and Save to send a manual selection instead."
                forCover ->
                    "Automatic mode scores chapters with the decision model until enough useful ones are found. " +
                        "Check chapters to send a manual selection instead, without the scan."
                else ->
                    "Automatic mode scores the opening chapters with the decision model and keeps those that " +
                        "establish the premise without spoilers. Check chapters to send a manual selection instead."
            },
        resetLabel = "Reset to automatic selection",
        save = { indices ->
            if (forCover) repository.setAiCoverContextChapters(story.id, indices) else repository.setAiContextChapters(story.id, indices)
        },
    )
}

private fun matchesChapterQuery(
    index: Int,
    title: String,
    query: String,
): Boolean = query.isEmpty() || "chapter ${index + 1} $title".contains(query, ignoreCase = true)
