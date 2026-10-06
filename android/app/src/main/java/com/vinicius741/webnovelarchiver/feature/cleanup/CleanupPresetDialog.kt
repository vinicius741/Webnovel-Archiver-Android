package com.vinicius741.webnovelarchiver.feature.cleanup

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.cleanup.RegexRuleCleanup
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeThemedSpinner
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun ScreenHost.showCleanupPresetPicker() {
    val presets = RegexCleanupPresets.Preset.values()
    AlertDialog
        .Builder(app)
        .setTitle("Choose a cleanup preset")
        .setItems(presets.map { it.title }.toTypedArray()) { _, index -> showCleanupPresetDialog(presets[index]) }
        .setNegativeButton("Cancel", null)
        .show()
        .applyAppTheme()
}

internal fun ScreenHost.showCleanupPresetDialog(
    preset: RegexCleanupPresets.Preset,
    existing: RegexCleanupRule? = null,
    initialCount: Int = preset.defaultCount,
) {
    val separator = preset == RegexCleanupPresets.Preset.SEPARATOR_LINES
    val view = FormLayout(app, dialog = true)
    val countLabel = if (separator) "Minimum symbols on a line" else "Maximum copies to keep"
    val count =
        makeField(
            app,
            initialCount.toString(),
            countLabel,
            InputType.TYPE_CLASS_NUMBER,
        )
    view.addItem(count, countLabel)
    val targets = listOf("tts", "download", "both")
    val target =
        makeThemedSpinner(app, listOf("Read aloud only", "Downloaded text only", "Both")).apply {
            setSelection(targets.indexOf(existing?.appliesTo ?: "tts").coerceAtLeast(0))
        }
    view.addItem(target, "Apply to")
    val before =
        EditText(app).apply {
            applyInputStyle("Text to test", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, singleLine = false)
            minLines = 3
            maxLines = 5
            setText(preset.sample)
        }
    view.addItem(before, "Before")
    val after =
        makeText(app, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(Spacing.MD), dp(Spacing.MD), dp(Spacing.MD), dp(Spacing.MD))
            minHeight = dp(64)
            background = roundedBg(ThemeManager.colors.elevation1, dp(6).toFloat())
            setTextIsSelectable(true)
        }
    view.addItem(after, "After")
    var previewGeneration = 0

    fun updatePreview() {
        val generation = ++previewGeneration
        val generated = RegexCleanupPresets.generate(preset, count.text.toString().toIntOrNull() ?: 0)
        if (generated == null) {
            after.text = "Enter a count from 1 to ${RegexCleanupPresets.MAX_COUNT}."
            return
        }
        val sample = before.text.toString()
        scope.launch(Dispatchers.Default) {
            val output = RegexRuleCleanup.previewRegexRule(generated.pattern, generated.flags, sample)
            app.runOnUiThread {
                if (generation == previewGeneration) after.text = output?.ifEmpty { "Empty text" } ?: "Enter text to preview."
            }
        }
    }
    count.doAfterTextChanged { updatePreview() }
    before.doAfterTextChanged { updatePreview() }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(preset.title)
            .setView(scroll(view))
            .setPositiveButton(if (existing == null) "Add rule" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()
    dialog.setOnShowListener {
        updatePreview()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val generated = RegexCleanupPresets.generate(preset, count.text.toString().toIntOrNull() ?: 0)
            if (generated == null) {
                count.error = "Enter a count from 1 to ${RegexCleanupPresets.MAX_COUNT}"
                return@setOnClickListener
            }
            val appliesTo = targets[target.selectedItemPosition]
            val rules = repository.regexRules.get().toMutableList()
            if (RegexRuleCleanup.hasSimilarRegexRule(rules, existing?.id, generated.pattern, generated.flags, appliesTo)) {
                toast("This rule already exists for the selected target")
                return@setOnClickListener
            }
            val rule =
                RegexCleanupRule(
                    id = existing?.id ?: "rule_${System.currentTimeMillis()}",
                    name = generated.name,
                    pattern = generated.pattern,
                    flags = generated.flags,
                    enabled = existing?.enabled ?: true,
                    appliesTo = appliesTo,
                )
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index >= 0) rules[index] = rule else rules.add(rule)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            scope.launch {
                repository.regexRules.save(rules)
                dialog.dismiss()
                showCleanupRules()
            }
        }
    }
    dialog.show()
    dialog.applyFormStyle()
}
