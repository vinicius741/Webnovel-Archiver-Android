package com.vinicius741.webnovelarchiver.feature.ai

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.listAiCoverVersions
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File-backed thumbnails keep the gallery from decoding every full image into screen state. */
internal fun ScreenHost.addAiCoverVersions(
    container: LinearLayout,
    story: Story,
) {
    val slot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    container.addView(slot)
    scope.launch {
        val versions = coverUiAttempt { repository.listAiCoverVersions(story.id) } ?: return@launch
        if (!frameIsAiControls(story.id) || versions.isEmpty()) return@launch
        slot.text("Saved covers (${versions.size})", Type.LABEL_MEDIUM)
        slot.text("Tap a version to compare or reuse its prompt. Covers stay on this device.", Type.BODY_SMALL)
        val strip = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        versions.forEachIndexed { index, version ->
            val item =
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(Space.SM), dp(Space.MD), dp(Space.SM))
                }
            val label = "Version ${versions.size - index}"
            item.addView(
                makeCover(app, 88, 132).apply {
                    loadImage(version.image, this)
                    contentDescription = "Compare $label"
                    setOnClickListener {
                        scope.launch {
                            val draft =
                                withContext(Dispatchers.IO) {
                                    runCatching { AiCoverDraft(version.prompt, version.image.readBytes(), version.mediaType) }.getOrNull()
                                }
                            if (draft ==
                                null
                            ) {
                                toast("This cover is no longer available")
                            } else {
                                showSavedCover(story, draft, label, version.isCurrent)
                            }
                        }
                    }
                },
            )
            item.text(label, Type.LABEL_SMALL)
            if (version.isCurrent) item.text("Current", Type.LABEL_SMALL)
            strip.addView(item)
        }
        slot.addView(HorizontalScrollView(app).apply { addView(strip) })
    }
}

private fun ScreenHost.showSavedCover(
    story: Story,
    draft: AiCoverDraft,
    label: String,
    currentVersion: Boolean,
) {
    if (!frameIsAiControls(story.id)) return
    val (dialog, content) = makeAiCoverDialog(label)
    addAiCoverDraftPreviewCard(
        content,
        repository.story(story.id) ?: return,
        draft,
        savedVersion = true,
        currentVersion = currentVersion,
    ) { dialog.dismiss() }
    content.fullButton("Close", Btn.TEXT) { dialog.dismiss() }
    dialog.show()
}

/** Reusing a prompt is free. The explicit Generate button starts the billable image request. */
internal fun ScreenHost.editAiCoverPrompt(
    story: Story,
    prompt: String,
) {
    val field =
        EditText(app).apply {
            applyInputStyle("Describe your cover", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, singleLine = false)
            setText(prompt)
            minLines = 3
            maxLines = 12
        }
    val (dialog, container) = makeAiCoverDialog("Try a different prompt")
    container.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    container.spacer(Space.MD)
    container.row {
        button("Cancel", Btn.TEXT) { dialog.dismiss() }
        button("Save prompt", Btn.FILLED) {
            val edited = field.text.toString().trim()
            if (edited.isBlank()) {
                field.error = "Enter an image description"
            } else if (aiCoverOperationFor(story.id) != null) {
                toast("Wait for cover generation to finish")
            } else {
                scope.launch {
                    coverUiAttempt {
                        repository.saveAiCoverPromptDraft(story.id, edited)
                        aiControlsScreenState.coverDrafts.remove(story.id)
                        aiControlsScreenState.coverPrompts[story.id] = edited
                        dialog.dismiss()
                        if (frameIsAiControls(story.id)) showAiControls(story.id)
                    }
                }
            }
        }
    }
    dialog.show()
}
