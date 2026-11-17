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
 * One spending card (the app's own log, replaced by OpenRouter's figures for the entered key when
 * they load) and the recent requests.
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

    container.section("Spending")
    lateinit var values: List<TextView>
    lateinit var source: TextView
    container.addView(
        container.card {
            values = statGrid(listOf("Today", "This month", "All time"))
            source = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(Space.SM), 0, 0) }
            addView(source)
        },
    )

    // The app's own log first; OpenRouter's figures replace it when they load.
    fun showLedger() {
        values[0].text = formatUsd(today.costUsd)
        values[1].text = formatUsd(month.costUsd)
        values[2].text = formatUsd(allTime.costUsd)
        source.text =
            listOfNotNull(
                "Requests made by this app",
                allTime.unknownCallCount.takeIf { it > 0 }?.let { "$it with unknown cost" },
            ).joinToString(" · ")
    }
    showLedger()
    val apiKey = apiKeyProvider()?.trim().orEmpty()
    if (apiKey.isNotBlank()) {
        scope.launch {
            val usage = runCatching { app.appContainer.openRouter.fetchCurrentKeyUsage(apiKey) }.getOrNull() ?: return@launch
            app.runOnUiThread {
                if (!source.isAttachedToWindow) return@runOnUiThread
                values[0].text = formatUsd(usage.usageDaily)
                values[1].text = formatUsd(usage.usageMonthly)
                values[2].text = formatUsd(usage.usage)
                source.text = OpenRouterUsageLine.of(usage)
            }
        }
    }

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
}

/** The line under OpenRouter's figures: whose usage it is, and the key's limit when it has one. */
private object OpenRouterUsageLine {
    fun of(usage: OpenRouterKeyUsage): String =
        listOfNotNull(
            "From OpenRouter: everything this key spent, in any app",
            usage.limit?.takeIf(String::isNotBlank)?.let { "limit ${formatUsd(it)}, ${formatUsd(usage.limitRemaining)} left" },
        ).joinToString(" · ")
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
