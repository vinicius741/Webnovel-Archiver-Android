package com.vinicius741.webnovelarchiver.feature.settings

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiUsagePeriod
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
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
    container.addView(
        container.card {
            text("Tracked by this app", Type.TITLE_SMALL)
            text(
                "Starts with requests made after this update. Live key totals below can also include older requests or usage outside this app.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            )
            spacer(Space.SM)
            usageRow(this, "Today", formatUsd(today.costUsd))
            usageRow(this, "This month", formatUsd(month.costUsd))
            usageRow(this, "All time", formatUsd(allTime.costUsd))
            usageRow(this, "Calls without a reported cost", allTime.unknownCallCount.toString())

            spacer(Space.MD)
            text("Recent requests", Type.TITLE_SMALL)
            spacer(Space.XS)
            if (ledger.recentRecords.isEmpty()) {
                text("No AI requests recorded yet.", Type.BODY_SMALL, colors.onSurfaceVariant)
            } else {
                val recentRecords = ledger.recentRecords.asReversed().take(MAX_RECENT_REQUESTS)
                recentRecords.forEachIndexed { index, record ->
                    recentRequestRow(this, record)
                    if (index < recentRecords.lastIndex) {
                        addView(makeDivider(context))
                    }
                }
            }

            spacer(Space.MD)
            text("Current OpenRouter key", Type.TITLE_SMALL)
            text(
                "Live counters from the API key entered above. This does not use a management key.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            )
            spacer(Space.SM)

            val keyStatus = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
            addView(keyStatus)
            val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(keyRows)
            var refreshButton: Button? = null

            fun clearKeyRows() {
                keyRows.removeAllViews()
            }

            fun renderKeyUnavailable(message: String) {
                clearKeyRows()
                keyStatus.text = message
                // Keep Refresh enabled even in the no-key state: the user may type an unsaved
                // draft into the field above and then retry without rebuilding this screen.
                refreshButton?.isEnabled = true
            }

            fun renderKeyUsage(usage: OpenRouterKeyUsage) {
                clearKeyRows()
                keyStatus.text = ""
                usageRow(keyRows, "Usage", formatKeyCost(usage.usage))
                usageRow(keyRows, "Today", formatKeyCost(usage.usageDaily))
                usageRow(keyRows, "This week", formatKeyCost(usage.usageWeekly))
                usageRow(keyRows, "This month", formatKeyCost(usage.usageMonthly))
                usageRow(keyRows, "Spending limit", formatKeyCost(usage.limit))
                usageRow(keyRows, "Remaining", formatKeyCost(usage.limitRemaining))
                usage.limitReset?.takeIf(String::isNotBlank)?.let { reset ->
                    usageRow(keyRows, "Limit reset", reset.replaceFirstChar { it.uppercaseChar() })
                }
                refreshButton?.isEnabled = true
            }

            fun refreshKeyUsage() {
                val apiKey = apiKeyProvider()?.trim().orEmpty()
                if (apiKey.isBlank()) {
                    renderKeyUnavailable("Add an OpenRouter API key above to view live usage.")
                    return
                }
                refreshButton?.isEnabled = false
                keyStatus.text = "Loading current key usage…"
                clearKeyRows()
                scope.launch {
                    val result = runCatching { app.appContainer.openRouter.fetchCurrentKeyUsage(apiKey) }
                    app.runOnUiThread {
                        result
                            .onSuccess { usage -> renderKeyUsage(usage) }
                            .onFailure { error ->
                                clearKeyRows()
                                keyStatus.text = error.message ?: "Current key usage is unavailable."
                                refreshButton?.isEnabled = true
                            }
                    }
                }
            }

            refreshButton =
                fullButton(
                    label = "Refresh",
                    variant = Btn.TEXT,
                    icon = R.drawable.wna_refresh,
                    topMarginDp = Space.SM,
                    bottomMarginDp = 0,
                ) {
                    refreshKeyUsage()
                }

            if (apiKeyProvider()?.isNullOrBlank() == true) {
                renderKeyUnavailable("Add an OpenRouter API key above to view live usage.")
            } else {
                refreshKeyUsage()
            }
        },
    )
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

private fun formatKeyCost(costString: String?): String = formatUsd(costString)

private fun formatUsd(costString: String?): String =
    costString
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { AiUsagePlanning.formatCostUsd(it) }.getOrDefault("Cost unavailable") }
        ?: "Unavailable"

private fun recentRequestRow(
    parent: ViewGroup,
    record: AiUsageRecord,
) {
    val context = parent.context
    val colors = ThemeManager.colors
    val feature =
        when (record.feature) {
            "description" -> "Description"
            "cover_prompt" -> "Cover prompt"
            "cover_image" -> "Cover image"
            else -> record.feature.takeIf(String::isNotBlank) ?: "AI request"
        }
    val model = record.model.takeIf(String::isNotBlank) ?: "Model unavailable"
    val outcome =
        when (record.outcome) {
            "completed" -> "Completed"
            "empty" -> "Empty reply"
            else -> record.outcome.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: "Unknown"
        }
    val requestDetails =
        buildList {
            add(formatUsd(record.costUsd))
            record.totalTokens?.let { add("$it tokens") }
            add(outcome)
        }.joinToString(" · ")
    parent.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(makeText(context, feature, Type.BODY_MEDIUM, colors.onSurface))
            addView(
                makeText(
                    context,
                    "$model · ${formatRequestTime(record.timestamp)}",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, context.dp(Space.XS), 0, 0) },
            )
            addView(
                makeText(
                    context,
                    requestDetails,
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, context.dp(Space.XS), 0, 0) },
            )
            setPadding(0, context.dp(Space.XS), 0, context.dp(Space.XS))
        },
    )
}

private fun formatRequestTime(timestamp: Long): String =
    runCatching {
        REQUEST_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault("Unknown time")

private val REQUEST_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

private const val MAX_RECENT_REQUESTS = 8
