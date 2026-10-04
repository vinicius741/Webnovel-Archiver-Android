package com.vinicius741.webnovelarchiver.feature.cleanup

import android.graphics.Typeface
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

internal fun ScreenHost.buildRegexRules(body: LinearLayout) {
    body.apply {
        text(
            "Use a preset to shorten repeated characters or remove separator lines. Try it on sample text before saving.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.SM)
        flow {
            button("Add preset", Btn.FILLED, R.drawable.wna_add) { showCleanupPresetPicker() }
            button("Add custom regex", Btn.TONAL, R.drawable.wna_add) { showRegexRuleDialog(null) }
        }
        section("Your rules")
        if (repository.getRegexRules().isEmpty()) {
            text("No regex rules yet. Add a preset above or create a custom rule.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        }
        repository.getRegexRules().forEach { rule ->
            val preset = RegexCleanupPresets.identify(rule)
            val target =
                when (rule.appliesTo) {
                    "tts" -> "Read aloud only"
                    "download" -> "Downloaded text only"
                    else -> "Read aloud and downloaded text"
                }
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
                                addView(
                                    makeText(
                                        context,
                                        "${if (rule.enabled) "Enabled" else "Disabled"} · $target",
                                        Type.BODY_SMALL,
                                        ThemeManager.colors.onSurfaceVariant,
                                    ),
                                )
                                if (preset == null) {
                                    addView(
                                        makeText(
                                            context,
                                            "/${rule.pattern}/${rule.flags}",
                                            Type.LABEL_SMALL,
                                            ThemeManager.colors.primary,
                                        ).apply {
                                            setPadding(0, dp(2), 0, 0)
                                            typeface = Typeface.MONOSPACE
                                            maxLines = 1
                                            ellipsize = TextUtils.TruncateAt.MIDDLE
                                        },
                                    )
                                }
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    flow {
                        button("Edit", Btn.TEXT, R.drawable.wna_edit) {
                            if (preset == null) showRegexRuleDialog(rule) else showCleanupPresetDialog(preset.preset, rule, preset.count)
                        }
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
