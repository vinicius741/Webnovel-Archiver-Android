package com.vinicius741.webnovelarchiver.feature.cleanup

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
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeThemedSpinner
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

internal fun ScreenHost.showRegexRuleDialog(
    model: CleanupScreenModel,
    existing: RegexCleanupRule?,
) {
    val view = FormLayout(app, dialog = true)
    val name = makeField(app, existing?.name.orEmpty(), "Rule name", InputType.TYPE_CLASS_TEXT)
    val pattern = makeField(app, existing?.pattern.orEmpty(), "Regex pattern", InputType.TYPE_CLASS_TEXT)
    val flags = makeField(app, existing?.flags ?: "i", "Flags, e.g. im", InputType.TYPE_CLASS_TEXT)
    val targets = listOf("both", "download", "tts")
    val appliesTo =
        makeThemedSpinner(app, listOf("Reading and listening", "Reading only", "Listening only")).apply {
            setSelection(targets.indexOf(existing?.appliesTo ?: "both").coerceAtLeast(0))
        }
    view.addItem(name)
    view.addItem(pattern)
    view.addItem(flags)
    view.addItem(appliesTo, "Apply to")
    // Live "test your rule" pane. The user pastes sample
    // text and the in-progress rule (pattern + flags) is applied to it in real time via
    // [RegexRuleCleanup.previewRegexRule], so they see what the rule removes before saving. Reuses the
    // same monospace preview styling as the preset dialog's.
    val previewInput =
        EditText(app).apply {
            applyInputStyle(
                "Text to test",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
            minLines = 2
            maxLines = 4
        }
    view.addItem(previewInput, "Before")
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
            minHeight = dp(52)
        }
    view.addItem(previewOutput, "After")

    // R19: preview matching runs off main (bounded input), and a newer keystroke discards the
    // stale result instead of letting a slow pattern jank or overwrite the editor.
    var previewGeneration = 0

    fun updatePreview() {
        val generation = ++previewGeneration
        val patternText = pattern.text.toString()
        val flagsText = flags.text.toString()
        val inputText = previewInput.text.toString()
        scope.launch {
            val cleaned = model.previewRule(patternText, flagsText, inputText)
            if (generation == previewGeneration) previewOutput.text = cleaned ?: "(No output)"
        }
    }
    pattern.doAfterTextChanged { updatePreview() }
    flags.doAfterTextChanged { updatePreview() }
    previewInput.doAfterTextChanged { updatePreview() }

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
            val error =
                model.saveRegexRule(
                    existing,
                    name.text.toString(),
                    pattern.text.toString(),
                    flags.text.toString(),
                    targets.getOrElse(appliesTo.selectedItemPosition) { "both" },
                )
            if (error != null) toast(error) else dialog.dismiss()
        }
    }
    dialog.show()
    dialog.applyFormStyle()
}
