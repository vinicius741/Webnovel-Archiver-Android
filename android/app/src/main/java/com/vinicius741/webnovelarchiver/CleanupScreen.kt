package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.RegexCleanupRule
import com.vinicius741.webnovelarchiver.core.SentenceRemovalPlanning
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showCleanupRules() {
    screen(title = "Text Cleanup", subtitle = "Sentence removal & regex rules", onBack = { showSettings() }) {
        flow {
            button("Export JSON", Btn.TONAL, R.drawable.wna_share) { share(storage.exportCleanupRules()) }
        }
        section("Sentences")
        row {
            val sentence = EditText(context).apply {
                hint = "Sentence to remove"
                setBackgroundColor(Color.TRANSPARENT)
                setHintTextColor(ThemeManager.colors.onSurfaceVariant)
                setTextColor(ThemeManager.colors.onSurface)
                setSingleLine()
            }
            addView(sentence, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.TONAL, R.drawable.wna_add) {
                val list = storage.getSentenceRemovalList()
                val result = SentenceRemovalPlanning.save(list, sentence.text.toString())
                if (!result.valid) {
                    toast(result.error ?: "Invalid sentence")
                } else {
                    storage.saveSentenceRemovalList(result.sentences)
                    showCleanupRules()
                }
            }
        }
        storage.getSentenceRemovalList().forEachIndexed { index, item ->
            addView(card {
                text(item, Type.BODY_MEDIUM)
                flow {
                    button("Edit", Btn.TEXT, R.drawable.wna_edit) {
                        prompt("Edit Sentence", item) { updated ->
                            val result = SentenceRemovalPlanning.save(storage.getSentenceRemovalList(), updated, index)
                            if (!result.valid) {
                                toast(result.error ?: "Invalid sentence")
                            } else {
                                storage.saveSentenceRemovalList(result.sentences)
                                showCleanupRules()
                            }
                        }
                    }
                    button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                        confirm("Are you sure you want to remove this sentence from the blocklist?") {
                            storage.saveSentenceRemovalList(SentenceRemovalPlanning.delete(storage.getSentenceRemovalList(), index))
                            showCleanupRules()
                        }
                    }
                }
            })
        }
        divider()
        section("Regex Rules")
        flow {
            button("Add Regex Rule", Btn.TONAL, R.drawable.wna_add) { showRegexRuleDialog(null) }
        }
        storage.getRegexRules().forEach { rule ->
            addView(card {
                row {
                    addView(ImageView(context).apply {
                        setImageDrawable(context.tintedIcon(if (rule.enabled) R.drawable.wna_check else R.drawable.wna_close, if (rule.enabled) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant))
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeText(context, rule.name, Type.TITLE_SMALL, ThemeManager.colors.onSurface))
                        addView(makeText(context, "/${rule.pattern}/${rule.flags} • ${rule.appliesTo}", Type.LABEL_SMALL, ThemeManager.colors.primary).apply { setPadding(0, dp(2), 0, 0) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                flow {
                    button("Edit", Btn.TEXT, R.drawable.wna_edit) { showRegexRuleDialog(rule) }
                    button(if (rule.enabled) "Disable" else "Enable", Btn.TEXT) { rule.enabled = !rule.enabled; storage.saveRegexRules(storage.getRegexRules()); showCleanupRules() }
                    button("Delete", Btn.TEXT, R.drawable.wna_delete) { storage.saveRegexRules(storage.getRegexRules().filterNot { it.id == rule.id }); showCleanupRules() }
                }
            })
        }
    }
}

internal fun ScreenHost.showRegexRuleDialog(existing: RegexCleanupRule?) {
    val view = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    val name = styledDialogField(existing?.name.orEmpty(), "Rule name")
    val pattern = styledDialogField(existing?.pattern.orEmpty(), "Regex pattern")
    val flags = styledDialogField(existing?.flags ?: "i", "Flags, e.g. im")
    val appliesTo = styledDialogField(existing?.appliesTo ?: "both", "download, tts, or both")
    view.addView(name)
    view.addView(pattern)
    view.addView(flags)
    view.addView(appliesTo)
    val quickRow = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; addView(makeButton(app, "Quick Separator", Btn.TONAL, R.drawable.wna_brush) {
        showQuickRegexBuilder { generated ->
            name.setText(generated.name)
            pattern.setText(generated.pattern)
            flags.setText(generated.flags)
        }
    }) }
    view.addView(quickRow)

    val dialog = AlertDialog.Builder(app)
        .setTitle(if (existing == null) "Add Regex Rule" else "Edit Regex Rule")
        .setView(view)
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val validation = TextCleanup.validateRegexRule(
                name.text.toString(),
                pattern.text.toString(),
                flags.text.toString(),
            )
            if (!validation.valid) {
                toast(validation.error ?: "Invalid regex rule")
                return@setOnClickListener
            }
            val normalizedTarget = when (appliesTo.text.toString().trim().lowercase()) {
                "download", "tts" -> appliesTo.text.toString().trim().lowercase()
                else -> "both"
            }
            val rules = storage.getRegexRules()
            if (TextCleanup.hasSimilarRegexRule(
                    rules,
                    existing?.id,
                    validation.normalizedPattern.orEmpty(),
                    validation.normalizedFlags.orEmpty(),
                    normalizedTarget,
                )
            ) {
                toast("A similar regex rule already exists")
                return@setOnClickListener
            }
            val updated = RegexCleanupRule(
                id = existing?.id ?: "rule_${System.currentTimeMillis()}",
                name = name.text.toString().trim(),
                pattern = validation.normalizedPattern.orEmpty(),
                flags = validation.normalizedFlags.orEmpty(),
                enabled = existing?.enabled ?: true,
                appliesTo = normalizedTarget,
            )
            val index = rules.indexOfFirst { it.id == updated.id }
            if (index >= 0) rules[index] = updated else rules.add(updated)
            storage.saveRegexRules(rules)
            dialog.dismiss()
            showCleanupRules()
        }
    }
    dialog.show()
}

internal fun ScreenHost.showQuickRegexBuilder(onGenerated: (TextCleanup.QuickPattern) -> Unit) {
    val view = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    val characters = styledDialogField("", "Character(s), e.g. =, -, ##")
    val minCount = styledDialogField("5", "Minimum repetitions")
    val wholeLine = CheckBox(app).apply {
        text = "Whole line only"
        isChecked = true
    }
    styledCheckBox(wholeLine)
    view.addView(characters)
    view.addView(minCount)
    view.addView(wholeLine)

    val dialog = AlertDialog.Builder(app)
        .setTitle("Quick Separator Rule")
        .setView(view)
        .setPositiveButton("Use Pattern", null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val generated = TextCleanup.generateQuickPattern(
                characters.text.toString(),
                minCount.text.toString().toIntOrNull() ?: 0,
                wholeLine.isChecked,
            )
            if (generated == null) {
                toast("Enter characters and a minimum count of at least 1")
                return@setOnClickListener
            }
            onGenerated(generated)
            dialog.dismiss()
        }
    }
    dialog.show()
}
