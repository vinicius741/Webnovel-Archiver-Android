package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
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
    screen(title = "Text Cleanup", subtitle = "Sentence removal & regex rules", onBack = { showSettings() }, scrollable = true) {
        // C4: group the export action under a header so the lone button isn't orphaned.
        section("Cleanup Rules")
        flow {
            button("Export JSON", Btn.TONAL, R.drawable.wna_share) { share(storage.exportCleanupRules()) }
        }
        divider()
        section("Sentences")
        // C2: the Add button fills the row so it matches the field height instead of floating right.
        row {
            val sentence = makeField(context, "", "Sentence to remove", InputType.TYPE_CLASS_TEXT)
            addView(
                sentence,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    gravity =
                        Gravity.CENTER_VERTICAL
                },
            )
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
        // C1: compact single-line rows (tap to edit, trailing delete) instead of a wall of full cards.
        storage.getSentenceRemovalList().forEachIndexed { index, item ->
            addView(
                compactRuleRow(item, onEdit = {
                    prompt("Edit Sentence", item) { updated ->
                        val result = SentenceRemovalPlanning.save(storage.getSentenceRemovalList(), updated, index)
                        if (!result.valid) {
                            toast(result.error ?: "Invalid sentence")
                        } else {
                            storage.saveSentenceRemovalList(result.sentences)
                            showCleanupRules()
                        }
                    }
                }, onDelete = {
                    confirm("Remove this sentence from the blocklist?", confirmLabel = "Delete") {
                        storage.saveSentenceRemovalList(SentenceRemovalPlanning.delete(storage.getSentenceRemovalList(), index))
                        showCleanupRules()
                    }
                }),
            )
        }
        divider()
        section("Regex Rules")
        flow {
            button("Add Regex Rule", Btn.TONAL, R.drawable.wna_add) { showRegexRuleDialog(null) }
        }
        storage.getRegexRules().forEach { rule ->
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
                            val toggled = storage.getRegexRules().map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }
                            storage.saveRegexRules(toggled)
                            showCleanupRules()
                        }
                        button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                            storage.saveRegexRules(
                                storage.getRegexRules().filterNot {
                                    it.id ==
                                        rule.id
                                },
                            )
                            ; showCleanupRules()
                        }
                    }
                },
            )
        }
    }
}

/**
 * Compact single-line rule row used by the sentence-removal list: one line of truncated text that
 * opens an edit prompt on tap, with a trailing delete icon. Avoids the repetitive full-card +
 * button-row wall that 10+ default rules produced (audit C1).
 */
private fun ScreenHost.compactRuleRow(
    text: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
): LinearLayout {
    val radiusPx = dp(8).toFloat()
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(6), dp(12))
            background = ripple(roundedBg(ThemeManager.colors.elevation1, radiusPx), radiusPx, ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { onEdit() }
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM + 2)
                }
        }
    row.addView(
        makeText(app, text, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        },
    )
    row.addView(
        ImageView(app).apply {
            setImageDrawable(app.tintedIcon(R.drawable.wna_delete, ThemeManager.colors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = selectableRipple(ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { onDelete() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        },
    )
    return row
}

internal fun ScreenHost.showRegexRuleDialog(existing: RegexCleanupRule?) {
    val view =
        LinearLayout(app).apply {
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
    val quickRow =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XS)
                }
            addView(
                makeButton(app, "Quick Separator", Btn.TONAL, R.drawable.wna_brush) {
                    showQuickRegexBuilder { generated ->
                        name.setText(generated.name)
                        pattern.setText(generated.pattern)
                        flags.setText(generated.flags)
                    }
                },
            )
        }
    view.addView(quickRow)

    // Gap 5: live "test your rule" pane — mirrors the legacy RN RuleDialog. The user pastes sample
    // text and the in-progress rule (pattern + flags) is applied to it in real time via
    // [TextCleanup.previewRegexRule], so they see what the rule removes before saving. Reuses the
    // same monospace preview styling as [showQuickRegexBuilder]'s pattern preview.
    view.addView(
        makeText(app, "Test Preview", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(Space.MD), 0, dp(Space.XS))
        },
    )
    val previewInput =
        EditText(app).apply {
            applyInputStyle(
                "Try text like ----- or ===== to test your rule",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
            minLines = 2
            maxLines = 4
        }
    view.addView(previewInput)
    val previewOutput =
        makeText(app, "(No output)", Type.BODY_SMALL, ThemeManager.colors.onSurface).apply {
            setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
            typeface = Typeface.MONOSPACE
            background =
                ripple(
                    roundedBg(ThemeManager.colors.elevation1, dp(6).toFloat()),
                    dp(6).toFloat(),
                    ThemeManager.colors.onSurface,
                )
            // Keep the box tall enough that a single-line output doesn't collapse it.
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XS)
                    bottomMargin = dp(Space.SM)
                }
            minHeight = dp(52)
        }
    view.addView(previewOutput)

    fun updatePreview() {
        val cleaned =
            TextCleanup.previewRegexRule(
                pattern.text.toString(),
                flags.text.toString(),
                previewInput.text.toString(),
            )
        previewOutput.text = cleaned ?: "(No output)"
    }
    pattern.addTextChangedListener(simpleTextWatcher { updatePreview() })
    flags.addTextChangedListener(simpleTextWatcher { updatePreview() })
    previewInput.addTextChangedListener(simpleTextWatcher { updatePreview() })

    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(if (existing == null) "Add Regex Rule" else "Edit Regex Rule")
            .setView(scroll(view))
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

    dialog.setOnShowListener {
        updatePreview()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val validation =
                TextCleanup.validateRegexRule(
                    name.text.toString(),
                    pattern.text.toString(),
                    flags.text.toString(),
                )
            if (!validation.valid) {
                toast(validation.error ?: "Invalid regex rule")
                return@setOnClickListener
            }
            val normalizedTarget =
                when (
                    appliesTo.text
                        .toString()
                        .trim()
                        .lowercase()
                ) {
                    "download", "tts" ->
                        appliesTo.text
                            .toString()
                            .trim()
                            .lowercase()
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
            val updated =
                RegexCleanupRule(
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
    dialog.applyAppTheme()
}

internal fun ScreenHost.showQuickRegexBuilder(onGenerated: (TextCleanup.QuickPattern) -> Unit) {
    val view =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
    val characters = styledDialogField("", "Character(s), e.g. =, -, ##")
    val minCount = styledDialogField("5", "Minimum repetitions")
    val wholeLine =
        CheckBox(app).apply {
            text = "Whole line only"
            isChecked = true
        }
    styledCheckBox(wholeLine)
    view.addView(characters)
    view.addView(minCount)
    view.addView(wholeLine)
    // C5: live preview of the pattern that will be generated, so the user sees what it matches
    // before tapping "Use Pattern".
    val preview =
        makeText(app, "Pattern preview: —", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(8), 0, dp(4))
            typeface = Typeface.MONOSPACE
        }
    view.addView(preview)
    val updatePreview = {
        val generated =
            TextCleanup.generateQuickPattern(
                characters.text.toString(),
                minCount.text.toString().toIntOrNull() ?: 0,
                wholeLine.isChecked,
            )
        preview.text =
            if (generated ==
                null
            ) {
                "Pattern preview: enter characters and count"
            } else {
                "Pattern preview: /${generated.pattern}/${generated.flags}"
            }
    }
    characters.addTextChangedListener(simpleTextWatcher { updatePreview() })
    minCount.addTextChangedListener(simpleTextWatcher { updatePreview() })
    wholeLine.setOnCheckedChangeListener { _, _ -> updatePreview() }

    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Quick Separator Rule")
            .setView(scroll(view))
            .setPositiveButton("Use Pattern", null)
            .setNegativeButton("Cancel", null)
            .create()
    dialog.setOnShowListener {
        updatePreview()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val generated =
                TextCleanup.generateQuickPattern(
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
    dialog.applyAppTheme()
}

/** Minimal TextWatcher that only forwards onTextChanged, for concise inline listeners. */
internal fun simpleTextWatcher(onChanged: () -> Unit) =
    object : android.text.TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) = Unit

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) = onChanged()

        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
