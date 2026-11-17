package com.vinicius741.webnovelarchiver.feature.settings

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
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
import kotlinx.coroutines.launch

private val SCAN_SPEC =
    StepperSpec(
        step = 1.0,
        min = AiSettings.MIN_COVER_EVIDENCE_CHAPTERS.toDouble(),
        max = AiSettings.MAX_COVER_EVIDENCE_CHAPTERS.toDouble(),
        label = "Chapter scan limit",
    )

/** A typed key not saved yet, so a re-render (fold) shows it rather than the older saved one. */
private var unsavedAiKey: String? = null

/** A typed key is saved once typing pauses this long (and when the page closes). */
private const val KEY_SAVE_DELAY_MS = 600L

/**
 * OpenRouter-backed AI settings (v3): the device-local API key (never backed up), the model
 * choices, spending, and an Advanced group with the chapter selection model and scan limit.
 * Everything saves as it changes.
 */
internal fun ScreenHost.showAiSettings() {
    rerender = { showAiSettings() }
    val saved = repository.aiSettings.get()
    val handler = Handler(Looper.getMainLooper())
    var pendingKey: String? = null
    val saveKey =
        Runnable {
            val key = pendingKey ?: return@Runnable
            pendingKey = null
            // The process scope: a flush on leaving must finish even if the activity goes away.
            app.appContainer.applicationScope.launch {
                try {
                    val current = repository.aiSettings.get()
                    val next = key.trim().ifBlank { null }
                    if (current.apiKey != next) repository.aiSettings.save(current.copy(apiKey = next))
                } finally {
                    if (unsavedAiKey === key) unsavedAiKey = null
                }
            }
        }
    screen(route = AppRoute.AiSettings, title = "AI settings", subtitle = "OpenRouter", scrollable = true) {
        capSettingsWidth(this)
        section("API key")
        val keyField =
            addApiKeyField(this, unsavedAiKey ?: saved.apiKey.orEmpty()) { key ->
                pendingKey = key
                unsavedAiKey = key
                handler.removeCallbacks(saveKey)
                handler.postDelayed(saveKey, KEY_SAVE_DELAY_MS)
            }
        settingsNote(
            "Get a key at openrouter.ai/keys. Requests use paid credits and share novel details and excerpts with model providers.",
        )
        section("Models")
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addAiModelsCard(this, advanced)
        showAiUsageSection(this) { keyField.text?.toString() }
        section("Advanced")
        addView(advanced)
        advanced.settingsGroup {
            groupStepper(
                "Chapter scan limit",
                "How many chapters automatic cover selection may read",
                saved.coverEvidenceChapters.toDouble(),
                SCAN_SPEC,
            ) { value ->
                scope.launch { repository.aiSettings.save(repository.aiSettings.get().copy(coverEvidenceChapters = value.toInt())) }
                null
            }
        }
        settingsNote("Picking chapters by hand on a novel skips the automatic scan.")
        // Leaving the page saves a key still waiting for the typing pause.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    handler.removeCallbacks(saveKey)
                    saveKey.run()
                }
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
