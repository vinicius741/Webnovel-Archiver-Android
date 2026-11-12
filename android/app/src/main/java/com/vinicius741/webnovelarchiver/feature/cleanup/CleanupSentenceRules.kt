package com.vinicius741.webnovelarchiver.feature.cleanup

import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.SentenceRemovalPlanning
import com.vinicius741.webnovelarchiver.feature.narration.makeListGroup
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.IconButtonStyle
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

internal fun ScreenHost.buildSentenceRules(body: LinearLayout) {
    body.apply {
        // The input holds a full sentence — usually a paragraph, sometimes a long one — so it is a
        // multiline textarea (minLines gives a comfortable editing area and it grows with the content)
        // rather than the compact single-line field shared with search/URL inputs.
        val sentence =
            EditText(context).apply {
                applyInputStyle(
                    "Sentence to remove",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                    singleLine = false,
                )
                setText(cleanupScreenState.sentenceDraft)
                doAfterTextChanged { cleanupScreenState.sentenceDraft = it.toString() }
                minLines = 4
                // Cap the visible height so the saved-sentence list below stays reachable on long
                // paragraphs; the field scrolls internally past that.
                maxLines = 10
            }
        addView(sentence)
        // Breathing room between the textarea and its Paste/Add actions, and a slightly larger gap
        // separating those actions from the saved-sentence list below — so neither edge feels cramped.
        spacer(Space.LG)
        // Paste + Add sit in a flow row beneath the textarea — a tall multiline field leaves no
        // room for a button beside it, and this mirrors the Add Story screen's paste affordance.
        flow(spacing = Space.SM) {
            button("Paste", Btn.SURFACE, R.drawable.wna_paste) {
                val clip = clipboardText()?.trim()
                if (clip.isNullOrEmpty()) {
                    toast("Clipboard is empty")
                } else {
                    sentence.setText(clip)
                    sentence.setSelection(clip.length)
                }
            }
            button("Add", Btn.FILLED, R.drawable.wna_add) {
                val list = repository.sentenceRemovalList.get()
                val result = SentenceRemovalPlanning.save(list, sentence.text.toString())
                if (!result.valid) {
                    toast(result.error ?: "Invalid sentence")
                } else {
                    scope.launch {
                        repository.sentenceRemovalList.save(result.sentences)
                        cleanupScreenState.sentenceDraft = ""
                        showCleanupRules()
                    }
                }
            }
        }
        section("Saved sentences")
        val sentences = repository.sentenceRemovalList.get()
        if (sentences.isEmpty()) {
            text("No sentences yet.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
            return
        }
        val group = makeListGroup(context)
        // Tap a sentence to edit it; the trailing delete removes it.
        sentences.forEachIndexed { index, item ->
            group.addView(
                sentenceRow(item, onEdit = {
                    prompt("Edit sentence", item) { updated ->
                        val result = SentenceRemovalPlanning.save(repository.sentenceRemovalList.get(), updated, index)
                        if (!result.valid) {
                            toast(result.error ?: "Invalid sentence")
                        } else {
                            scope.launch {
                                repository.sentenceRemovalList.save(result.sentences)
                                showCleanupRules()
                            }
                        }
                    }
                }, onDelete = {
                    confirm("Remove this sentence from the list?", confirmLabel = "Delete") {
                        scope.launch {
                            repository.sentenceRemovalList.save(
                                SentenceRemovalPlanning.delete(repository.sentenceRemovalList.get(), index),
                            )
                            showCleanupRules()
                        }
                    }
                }),
            )
        }
        addView(group)
    }
}

/** A saved sentence: up to three lines of it, tap to edit, trailing delete. */
private fun ScreenHost.sentenceRow(
    text: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ROW_MIN_DP)
        setPadding(dp(Space.LG), dp(Space.SM), dp(Space.XS), dp(Space.SM))
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onEdit() }
        addView(
            makeText(app, text, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
                textSize = SENTENCE_SP
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(Space.XS) },
        )
        addView(
            app.iconButton(
                R.drawable.wna_delete,
                "Delete sentence",
                ThemeManager.colors.onSurfaceVariant,
                IconButtonStyle.Small,
            ) { onDelete() },
        )
    }

private const val ROW_MIN_DP = 52
private const val SENTENCE_SP = 13.5f
