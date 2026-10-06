package com.vinicius741.webnovelarchiver.feature.cleanup

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.SentenceRemovalPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.section
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
        flow {
            button("Paste", Btn.TONAL, R.drawable.wna_paste) {
                val clip = clipboardText()?.trim()
                if (clip.isNullOrEmpty()) {
                    toast("Clipboard is empty")
                } else {
                    sentence.setText(clip)
                    sentence.setSelection(clip.length)
                }
            }
            button("Add", Btn.TONAL, R.drawable.wna_add) {
                val list = repository.getSentenceRemovalList()
                val result = SentenceRemovalPlanning.save(list, sentence.text.toString())
                if (!result.valid) {
                    toast(result.error ?: "Invalid sentence")
                } else {
                    scope.launch {
                        repository.saveSentenceRemovalList(result.sentences)
                        cleanupScreenState.sentenceDraft = ""
                        showCleanupRules()
                    }
                }
            }
        }
        spacer(Space.MD)
        section("Saved sentences")
        if (repository.getSentenceRemovalList().isEmpty()) {
            text("No saved sentences yet.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        }
        // C1: compact single-line rows (tap to edit, trailing delete) instead of a wall of full cards.
        repository.getSentenceRemovalList().forEachIndexed { index, item ->
            addView(
                compactRuleRow(item, onEdit = {
                    prompt("Edit Sentence", item) { updated ->
                        val result = SentenceRemovalPlanning.save(repository.getSentenceRemovalList(), updated, index)
                        if (!result.valid) {
                            toast(result.error ?: "Invalid sentence")
                        } else {
                            scope.launch {
                                repository.saveSentenceRemovalList(result.sentences)
                                showCleanupRules()
                            }
                        }
                    }
                }, onDelete = {
                    confirm("Remove this sentence from the blocklist?", confirmLabel = "Delete") {
                        scope.launch {
                            repository.saveSentenceRemovalList(
                                SentenceRemovalPlanning.delete(repository.getSentenceRemovalList(), index),
                            )
                            showCleanupRules()
                        }
                    }
                }),
            )
        }
    }
}
