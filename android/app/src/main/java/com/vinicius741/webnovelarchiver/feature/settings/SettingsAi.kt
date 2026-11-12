package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.feature.ai.addAiModelsCard
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/** The key and scan limit typed on AI settings but not saved yet; survives a fold re-render. */
private data class AiSettingsDraft(
    val apiKey: String,
    val scanLimit: Int,
)

private var aiDraft: AiSettingsDraft? = null

private val SCAN_SPEC =
    StepperSpec(
        step = 1.0,
        min = AiSettings.MIN_COVER_EVIDENCE_CHAPTERS.toDouble(),
        max = AiSettings.MAX_COVER_EVIDENCE_CHAPTERS.toDouble(),
        label = "Chapter scan limit",
    )

/**
 * OpenRouter-backed AI settings (v3): the device-local API key (never backed up), the global model
 * choices (saved as picked), the automatic cover scan limit, and spend. Key and scan limit save
 * from the docked Save bar.
 */
internal fun ScreenHost.showAiSettings(keepDraft: Boolean = false) {
    rerender = { showAiSettings(keepDraft = true) }
    val saved = repository.aiSettings.get()
    val savedDraft = AiSettingsDraft(saved.apiKey.orEmpty(), saved.coverEvidenceChapters)
    if (!keepDraft || aiDraft == null) aiDraft = savedDraft
    lateinit var bar: DockedSaveBar

    fun update(change: (AiSettingsDraft) -> AiSettingsDraft) {
        val next = change(aiDraft ?: savedDraft)
        aiDraft = next
        val now = repository.aiSettings.get()
        bar.show(next != AiSettingsDraft(now.apiKey.orEmpty(), now.coverEvidenceChapters))
    }
    bar =
        DockedSaveBar(app, "Save") {
            val draft = aiDraft ?: return@DockedSaveBar
            scope.launch {
                repository.aiSettings.save(
                    repository.aiSettings.get().copy(
                        apiKey = draft.apiKey.trim().ifBlank { null },
                        coverEvidenceChapters = draft.scanLimit,
                    ),
                )
                toast("AI settings saved")
                bar.show(false)
            }
        }
    screen(route = AppRoute.AiSettings, title = "AI settings", subtitle = "OpenRouter", fullWidthBody = true) {
        scrollWithDockedBar(this, bar) {
            section("API key")
            val keyField = addApiKeyField(this, aiDraft?.apiKey.orEmpty()) { key -> update { it.copy(apiKey = key) } }
            settingsNote(
                "Get a key at openrouter.ai/keys. Requests use paid credits and share novel details and excerpts with model providers.",
            )
            section("Models")
            addAiModelsCard(this)
            section("Automatic cover selection")
            settingsGroup {
                groupStepper(
                    "Chapter scan limit",
                    "How many chapters the AI may read",
                    (aiDraft?.scanLimit ?: saved.coverEvidenceChapters).toDouble(),
                    SCAN_SPEC,
                ) { value ->
                    update { it.copy(scanLimit = value.toInt()) }
                    null
                }
            }
            settingsNote("Picking chapters by hand skips the scan.")
            showAiUsageSection(this) { keyField.text?.toString() }
        }
        bar.show(aiDraft != savedDraft)
    }
}

/** A password field with a show/hide eye; reports every edit. */
private fun ScreenHost.addApiKeyField(
    container: LinearLayout,
    value: String,
    onChange: (String) -> Unit,
): EditText {
    val colors = ThemeManager.colors
    val field =
        makeField(app, value, "sk-or-v1-…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            // setSingleLine can drop the password transformation on some Android builds even when
            // TYPE_TEXT_VARIATION_PASSWORD remains in inputType.
            transformationMethod = PasswordTransformationMethod.getInstance()
            contentDescription = "OpenRouter API key"
            doAfterTextChanged { onChange(it?.toString().orEmpty()) }
        }
    var shown = false
    lateinit var eye: android.widget.ImageView
    eye =
        makeRowIconButton(app, R.drawable.wna_visibility, "Show key") {
            shown = !shown
            val selection = field.selectionEnd
            field.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
            field.setSelection(selection.coerceIn(0, field.text.length))
            eye.setImageDrawable(app.tintedIcon(R.drawable.wna_visibility, if (shown) colors.primary else colors.onSurfaceVariant))
            eye.contentDescription = if (shown) "Hide key" else "Show key"
        }
    container.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(Spacing.XS) })
            addView(eye)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return field
}
