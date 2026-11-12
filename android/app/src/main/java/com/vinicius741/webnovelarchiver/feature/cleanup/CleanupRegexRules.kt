package com.vinicius741.webnovelarchiver.feature.cleanup

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.feature.settings.makePill
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.launch

internal fun ScreenHost.buildRegexRules(body: LinearLayout) {
    val colors = ThemeManager.colors
    val rules = repository.regexRules.get()
    if (rules.isEmpty()) body.text("No regex rules yet.", Type.BODY_SMALL, colors.onSurfaceVariant)
    rules.forEach { rule ->
        val preset = RegexCleanupPresets.identify(rule)
        val target =
            when (rule.appliesTo) {
                "tts" -> "Read aloud only"
                "download" -> "Downloaded text only"
                else -> "Read aloud and downloaded text"
            }
        body.addView(
            body.card {
                alpha = if (rule.enabled) 1f else DISABLED_ALPHA
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            makeText(context, rule.name, Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(Spacing.SM) },
                        )
                        addView(
                            if (rule.enabled) {
                                makePill(context, "Enabled", colors.primaryContainer, colors.onPrimaryContainer)
                            } else {
                                makePill(context, "Disabled", colors.elevation4, colors.onSurfaceVariant)
                            },
                        )
                    },
                )
                addView(makeText(context, target, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
                if (preset == null) {
                    addView(
                        makeText(context, "/${rule.pattern}/${rule.flags}", Type.BODY_SMALL, colors.primary).apply {
                            typeface = Typeface.MONOSPACE
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                            val pad = dp(Spacing.MD - 2)
                            setPadding(pad + 2, pad, pad + 2, pad)
                            background = roundedBg(colors.background, dp(CODE_RADIUS_DP).toFloat())
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(Spacing.SM + 2)
                        },
                    )
                }
                addView(
                    WrapLayout(context).apply {
                        horizontalSpacingDp = Spacing.SM
                        verticalSpacingDp = Spacing.SM
                        addView(
                            makeButton(context, "Edit", Btn.SURFACE) {
                                if (preset ==
                                    null
                                ) {
                                    showRegexRuleDialog(rule)
                                } else {
                                    showCleanupPresetDialog(preset.preset, rule, preset.count)
                                }
                            },
                        )
                        addView(
                            makeButton(context, if (rule.enabled) "Disable" else "Enable", Btn.SURFACE) {
                                val toggled =
                                    repository.regexRules.get().map {
                                        if (it.id ==
                                            rule.id
                                        ) {
                                            it.copy(enabled = !it.enabled)
                                        } else {
                                            it
                                        }
                                    }
                                scope.launch {
                                    repository.regexRules.save(toggled)
                                    showCleanupRules()
                                }
                            },
                        )
                        addView(
                            makeButton(context, "Delete", Btn.TEXT) {
                                confirm("Delete the rule \"${rule.name}\"?", confirmLabel = "Delete") {
                                    scope.launch {
                                        repository.regexRules.save(repository.regexRules.get().filterNot { it.id == rule.id })
                                        showCleanupRules()
                                    }
                                }
                            }.apply { setTextColor(colors.error) },
                        )
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Spacing.MD)
                    },
                )
            },
        )
    }
    body.addView(
        WrapLayout(body.context).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
            addView(makeButton(context, "Add preset", Btn.TONAL, R.drawable.wna_add) { showCleanupPresetPicker() })
            addView(makeButton(context, "Add custom regex", Btn.SURFACE, R.drawable.wna_add) { showRegexRuleDialog(null) })
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = body.context.dp(Spacing.XS)
        },
    )
}

private const val DISABLED_ALPHA = 0.65f
private const val TITLE_SP = 15f
private const val CODE_RADIUS_DP = 10
