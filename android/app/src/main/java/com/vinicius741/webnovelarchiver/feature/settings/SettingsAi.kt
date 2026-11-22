package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
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

private val SCAN_SPEC =
    StepperSpec(
        step = 1.0,
        min = AiSettings.MIN_COVER_EVIDENCE_CHAPTERS.toDouble(),
        max = AiSettings.MAX_COVER_EVIDENCE_CHAPTERS.toDouble(),
        label = "Chapter scan limit",
    )

/** AI settings' reads and saves. */
private class HostAiSettingsData(
    private val host: ScreenHost,
) : AiSettingsData {
    override fun settings(): AiSettings = host.repository.aiSettings.get()

    override suspend fun save(settings: AiSettings) = host.repository.aiSettings.save(settings)

    override fun ledger(): AiUsageLedger = host.repository.getAiUsageLedger()

    override suspend fun keyUsage(apiKey: String): OpenRouterKeyUsage =
        host.app.appContainer.openRouter
            .fetchCurrentKeyUsage(apiKey)

    override fun now(): Long = System.currentTimeMillis()
}

/** AI settings' model, kept while it is in the history. */
private fun ScreenHost.aiSettingsModel(): AiSettingsScreenModel =
    screenModels.get(AppRoute.AiSettings.stableKey) {
        // The process scope: a key saved on leaving must finish even if the activity goes away.
        AiSettingsScreenModel(HostAiSettingsData(this), app.appContainer.applicationScope)
    }

/**
 * OpenRouter-backed AI settings (v3): the device-local API key (never backed up), the model
 * choices, spending, and an Advanced group with the chapter selection model and scan limit.
 * Everything saves as it changes, through [AiSettingsScreenModel].
 */
internal fun ScreenHost.showAiSettings() {
    screen(route = AppRoute.AiSettings, title = "AI settings", subtitle = "OpenRouter", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = aiSettingsModel()
        model.refresh()
        val saved = model.state.value.settings
        capSettingsWidth(this)
        section("API key")
        addApiKeyField(this, model.apiKey) { key -> model.setApiKey(key) }
        settingsNote(
            "Get a key at openrouter.ai/keys. Requests use paid credits and share novel details and excerpts with model providers.",
        )
        section("Models")
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addAiModelsCard(this, advanced, model)
        showAiUsageSection(this, model)
        section("Advanced")
        addView(advanced)
        advanced.settingsGroup {
            groupStepper(
                "Chapter scan limit",
                "How many chapters automatic cover selection may read",
                saved.coverEvidenceChapters.toDouble(),
                SCAN_SPEC,
            ) { value ->
                model.setCoverEvidenceChapters(value.toInt())
                null
            }
        }
        settingsNote("Picking chapters by hand on a novel skips the automatic scan.")
        // Leaving the page saves a key still waiting for the typing pause.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) = model.flush()
            },
        )
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
