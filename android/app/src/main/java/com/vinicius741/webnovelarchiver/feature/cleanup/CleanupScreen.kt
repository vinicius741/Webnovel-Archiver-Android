package com.vinicius741.webnovelarchiver.feature.cleanup

import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.SentenceRemovalPlanning
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.story.share
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.divider
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

internal fun ScreenHost.showCleanupRules() {
    screen(
        route = AppRoute.CleanupRules,
        title = "Text Cleanup",
        subtitle = "Sentence removal & regex rules",
        onBack = { showSettings() },
        scrollable = true,
    ) {
        // C4: group the export action under a header so the lone button isn't orphaned.
        section("Cleanup Rules")
        flow {
            button("Export JSON", Btn.TONAL, R.drawable.wna_share) {
                scope.launch { share(repository.exportCleanupRules()) }
            }
        }
        divider()
        section("Sentences")
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
                minLines = 4
                // Cap the visible height so the saved-sentence list below stays reachable on long
                // paragraphs; the field scrolls internally past that.
                maxLines = 10
            }
        addView(sentence)
        // Breathing room between the textarea and its Paste/Add actions, and a slightly larger gap
        // separating those actions from the saved-sentence list below — so neither edge feels cramped.
        spacer(Space.SM)
        // Paste + Add sit in their own row beneath the textarea. With a tall multiline field the old
        // layout (button pinned beside the field, stretched to its height) no longer makes sense, so
        // the actions drop to a flow row below — mirroring the Add Story screen's paste affordance.
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
                        showCleanupRules()
                    }
                }
            }
        }
        spacer(Space.MD)
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
        divider()
        section("Regex Rules")
        flow {
            button("Add Regex Rule", Btn.TONAL, R.drawable.wna_add) { showRegexRuleDialog(null) }
        }
        repository.getRegexRules().forEach { rule ->
            addView(
                card {
                    row {
                        addView(
                            ImageView(context).apply {
                                setImageDrawable(
                                    context.tintedIcon(
                                        if (rule.enabled) R.drawable.wna_check else R.drawable.wna_close,
                                        if (rule.enabled) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant,
                                    ),
                                )
                                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                            },
                        )
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(makeText(context, rule.name, Type.TITLE_SMALL, ThemeManager.colors.onSurface))
                                // C3: truncate + monospace the raw regex so long patterns don't wrap uglily.
                                addView(
                                    makeText(
                                        context,
                                        "/${rule.pattern}/${rule.flags} • ${rule.appliesTo}",
                                        Type.LABEL_SMALL,
                                        ThemeManager.colors.primary,
                                    ).apply {
                                        setPadding(0, dp(2), 0, 0)
                                        typeface = Typeface.MONOSPACE
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.MIDDLE
                                    },
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    flow {
                        button("Edit", Btn.TEXT, R.drawable.wna_edit) { showRegexRuleDialog(rule) }
                        button(if (rule.enabled) "Disable" else "Enable", Btn.TEXT) {
                            val toggled = repository.getRegexRules().map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }
                            scope.launch {
                                repository.saveRegexRules(toggled)
                                showCleanupRules()
                            }
                        }
                        button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                            scope.launch {
                                repository.saveRegexRules(
                                    repository.getRegexRules().filterNot { it.id == rule.id },
                                )
                                showCleanupRules()
                            }
                        }
                    }
                },
            )
        }
    }
}
