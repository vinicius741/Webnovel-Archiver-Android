package com.vinicius741.webnovelarchiver.feature.settings

import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiUsagePeriod
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the local usage ledger and the live counters for the currently entered OpenRouter key.
 * The key provider reads the field at refresh time so refreshing never rebuilds Settings AI and
 * never discards an unsaved API-key draft.
 */
internal fun ScreenHost.showAiUsageSection(
    container: LinearLayout,
    apiKeyProvider: () -> String?,
) {
    val colors = ThemeManager.colors
    val ledger = repository.getAiUsageLedger()
    val now = System.currentTimeMillis()
    val today = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.TODAY, now)
    val month = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.CURRENT_MONTH, now)
    val allTime = AiUsagePlanning.summaryForPeriod(ledger, AiUsagePeriod.ALL_TIME, now)

    container.section("AI spend")
    val spend =
        container.card {
            val values = statGrid(listOf("Today", "This month", "All time"))
            values[0].text = formatUsd(today.costUsd)
            values[1].text = formatUsd(month.costUsd)
            values[2].text = formatUsd(allTime.costUsd)
            if (allTime.unknownCallCount > 0) {
                text(
                    "${allTime.unknownCallCount} calls with unknown cost",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).setPadding(0, dp(Space.SM), 0, 0)
            }
        }
    container.addView(spend)

    container.section("Recent requests")
    if (ledger.recentRecords.isEmpty()) {
        container.settingsNote("No AI requests recorded yet.")
    } else {
        container.settingsGroup {
            ledger.recentRecords
                .asReversed()
                .take(MAX_RECENT_REQUESTS)
                .forEach { recentRequestRow(this, it) }
        }
    }

    container.section("Current OpenRouter key")
    container.addView(
        container.card {
            val keyStatus = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
            val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            var refreshButton: Button? = null
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(makeText(context, "Key usage", Type.TITLE_SMALL, colors.onSurface))
                            addView(makeText(context, "All usage for this key, across apps", Type.BODY_SMALL, colors.onSurfaceVariant))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    refreshButton =
                        makeButton(context, "Refresh", Btn.SURFACE, R.drawable.wna_refresh) {
                            refreshKeyUsage(keyStatus, keyRows, apiKeyProvider) { refreshButton }
                        }
                    addView(refreshButton)
                },
            )
            addView(keyStatus.apply { setPadding(0, dp(Space.SM), 0, 0) })
            addView(keyRows)
            if (apiKeyProvider().isNullOrBlank()) {
                keyStatus.text = "Add a key to see usage."
            } else {
                refreshKeyUsage(keyStatus, keyRows, apiKeyProvider) { refreshButton }
            }
        },
    )
}

private fun ScreenHost.refreshKeyUsage(
    keyStatus: TextView,
    keyRows: LinearLayout,
    apiKeyProvider: () -> String?,
    refreshButton: () -> Button?,
) {
    val apiKey = apiKeyProvider()?.trim().orEmpty()
    keyRows.removeAllViews()
    if (apiKey.isBlank()) {
        // Refresh stays enabled: the user may type a key above and retry without a rebuild.
        keyStatus.text = "Add a key to see usage."
        return
    }
    refreshButton()?.isEnabled = false
    keyStatus.text = "Loading usage…"
    scope.launch {
        val result = runCatching { app.appContainer.openRouter.fetchCurrentKeyUsage(apiKey) }
        app.runOnUiThread {
            refreshButton()?.isEnabled = true
            result
                .onSuccess { usage -> renderKeyUsage(keyStatus, keyRows, usage) }
                .onFailure { error -> keyStatus.text = error.message ?: "Usage unavailable." }
        }
    }
}

private fun renderKeyUsage(
    keyStatus: TextView,
    keyRows: LinearLayout,
    usage: OpenRouterKeyUsage,
) {
    keyRows.removeAllViews()
    keyStatus.text = ""
    usageRow(keyRows, "Usage", formatUsd(usage.usage))
    usageRow(keyRows, "Today", formatUsd(usage.usageDaily))
    usageRow(keyRows, "This week", formatUsd(usage.usageWeekly))
    usageRow(keyRows, "This month", formatUsd(usage.usageMonthly))
    usageRow(keyRows, "Spending limit", formatUsd(usage.limit))
    usageRow(keyRows, "Remaining", formatUsd(usage.limitRemaining))
    usage.limitReset?.takeIf(String::isNotBlank)?.let { reset ->
        usageRow(keyRows, "Limit reset", reset.replaceFirstChar { it.uppercaseChar() })
    }
}

private fun usageRow(
    parent: ViewGroup,
    label: String,
    value: String,
) {
    val context = parent.context
    val colors = ThemeManager.colors
    parent.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                makeText(context, label, Type.BODY_MEDIUM, colors.onSurfaceVariant),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(makeText(context, value, Type.BODY_MEDIUM, colors.onSurface))
            setPadding(0, context.dp(Space.XS), 0, context.dp(Space.XS))
        },
    )
}

internal fun formatUsd(costString: String?): String =
    costString
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { AiUsagePlanning.formatCostUsd(it) }.getOrDefault("Cost unavailable") }
        ?: "Unavailable"

private fun recentRequestRow(
    parent: LinearLayout,
    record: AiUsageRecord,
) {
    val context = parent.context
    val feature =
        when (record.feature) {
            "description" -> "Description"
            "cover_selection" -> "Automatic cover selection"
            "description_selection" -> "Automatic description selection"
            "cover_prompt" -> "Cover prompt"
            "cover_image" -> "Cover image"
            else -> record.feature.takeIf(String::isNotBlank) ?: "AI request"
        }
    val model = record.model.takeIf(String::isNotBlank) ?: "Model unavailable"
    val outcome =
        when (record.outcome) {
            "completed" -> null
            "empty" -> "Empty reply"
            else -> record.outcome.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: "Unknown"
        }
    val details =
        listOfNotNull(
            model,
            record.totalTokens?.let {
                "$it tokens"
            },
            formatRequestTime(record.timestamp),
            outcome,
        ).joinToString(" · ")
    val cost =
        makeText(context, formatUsd(record.costUsd), Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(context.dp(Space.SM), 0, context.dp(Space.SM), 0)
        }
    val icon = if (record.feature.startsWith("cover")) R.drawable.wna_image else R.drawable.wna_notes
    parent.addView(makeListRow(context, feature, details, leadIcon = icon, leadTint = ThemeManager.colors.secondary, trailing = cost))
}

private fun formatRequestTime(timestamp: Long): String =
    runCatching {
        REQUEST_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault("Unknown time")

private val REQUEST_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

private const val MAX_RECENT_REQUESTS = 8
