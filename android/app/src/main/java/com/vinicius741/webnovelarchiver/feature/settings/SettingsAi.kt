package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.labeledField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * OpenRouter-backed AI feature settings: the shared API key plus usage. The per-feature model
 * choices and context-chapter selection live on the AI Controls screen, where generation happens.
 * The API key is device-local and never included in backups.
 */
internal fun ScreenHost.showAiSettings() {
    val settings = repository.aiSettings.get()
    screen(route = AppRoute.AiSettings, title = "AI Settings", onBack = { showSettings() }, scrollable = true) {
        section("OpenRouter")
        text(
            "Get a key at openrouter.ai/keys. Requests use paid credits and share novel details " +
                "and chapter excerpts with OpenRouter and model providers.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.MD)
        var apiKeyField: EditText? = null
        addView(
            card {
                apiKeyField =
                    labeledField(
                        "API key",
                        settings.apiKey.orEmpty(),
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        hint = "sk-or-v1-...",
                    ).apply {
                        // setSingleLine can drop the password transformation on some Android
                        // builds even when TYPE_TEXT_VARIATION_PASSWORD remains in inputType.
                        transformationMethod = PasswordTransformationMethod.getInstance()
                    }
            },
        )
        section("Automatic cover selection")
        text(
            "Scans chapters for cover ideas using OpenRouter credits. " +
                "Picking chapters manually skips the scan.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        val evidenceChaptersField =
            labeledField(
                "Chapter scan limit",
                settings.coverEvidenceChapters.toString(),
                InputType.TYPE_CLASS_NUMBER,
                hint = "${AiSettings.MIN_COVER_EVIDENCE_CHAPTERS}–${AiSettings.MAX_COVER_EVIDENCE_CHAPTERS}",
            )
        showAiUsageSection(this) { apiKeyField?.text?.toString() }
        fullButton("Save", Btn.FILLED, R.drawable.wna_check, topMarginDp = Space.LG, bottomMarginDp = Space.MD) {
            scope.launch {
                repository.aiSettings.save(
                    repository.aiSettings.get().copy(
                        apiKey = apiKeyField?.text?.toString(),
                        coverEvidenceChapters =
                            evidenceChaptersField.text
                                .toString()
                                .trim()
                                .toIntOrNull()
                                ?: repository.aiSettings.get().coverEvidenceChapters,
                    ),
                )
                toast("AI settings saved")
            }
        }
    }
}
