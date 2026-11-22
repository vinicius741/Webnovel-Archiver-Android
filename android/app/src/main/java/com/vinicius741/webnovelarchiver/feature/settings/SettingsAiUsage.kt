package com.vinicius741.webnovelarchiver.feature.settings

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.ai.RecentAiRequestGroup
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.feature.narration.makeListGroup
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One spending card (the app's own log, replaced by OpenRouter's figures for the entered key when
 * they load) and the recent requests, from [model]'s state.
 */
internal fun ScreenHost.showAiUsageSection(
    container: LinearLayout,
    model: AiSettingsScreenModel,
) {
    val colors = ThemeManager.colors
    val spending = model.state.value.spending

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
    fun show(usage: OpenRouterKeyUsage?) {
        if (usage == null) {
            values[0].text = formatUsd(spending.today.costUsd)
            values[1].text = formatUsd(spending.month.costUsd)
            values[2].text = formatUsd(spending.allTime.costUsd)
            source.text =
                listOfNotNull(
                    "Requests made by this app",
                    spending.allTime.unknownCallCount
                        .takeIf { it > 0 }
                        ?.let { "$it with unknown cost" },
                ).joinToString(" · ")
        } else {
            values[0].text = formatUsd(usage.usageDaily)
            values[1].text = formatUsd(usage.usageMonthly)
            values[2].text = formatUsd(usage.usage)
            source.text = OpenRouterUsageLine.of(usage)
        }
    }
    source.bindWhileAttached(scope, model.state.map { it.keyUsage }.distinctUntilChanged()) { show(it) }
    show(model.state.value.keyUsage)

    container.section("Recent requests")
    if (spending.recent.isEmpty()) {
        container.settingsNote("No AI requests recorded yet.")
    } else {
        container.settingsGroup {
            spending.recent.forEach { recentRequestRow(this, it) }
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
    group: RecentAiRequestGroup,
) {
    val context = parent.context
    val feature = featureLabel(group.feature)
    val model =
        when (group.models.size) {
            0 -> "Model unavailable"
            1 -> group.models.first()
            else -> "${group.models.first()} +${group.models.size - 1}"
        }
    val details =
        listOfNotNull(
            model,
            group.requestCount.takeIf { it > 1 }?.let { "$it requests" },
            group.totalTokens?.let { tokens ->
                if (group.unknownTokenCount > 0) "≥$tokens tokens" else "$tokens tokens"
            },
            formatRequestTime(group.latestTimestamp),
            groupOutcomeLabel(group.records),
            group.unknownCostCount.takeIf { it > 0 && group.costUsd != null }?.let { "$it cost unknown" },
        ).joinToString(" · ")
    val cost =
        makeText(context, formatUsd(group.costUsd), Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(context.dp(Space.SM), 0, context.dp(Space.SM), 0)
        }
    val icon =
        if (group.feature.startsWith("cover") || group.feature.startsWith("portrait")) {
            R.drawable.wna_image
        } else {
            R.drawable.wna_notes
        }
    val expandable = group.requestCount > 1
    parent.addView(
        makeListRow(
            context,
            feature,
            details,
            leadIcon = icon,
            leadTint = ThemeManager.colors.secondary,
            trailing = cost,
            chevron = expandable,
            onClick = if (expandable) ({ showRequestGroupSheet(context, feature, group) }) else null,
        ),
    )
}

private fun showRequestGroupSheet(
    context: Context,
    title: String,
    group: RecentAiRequestGroup,
) {
    val subtitle =
        listOfNotNull(
            "${group.requestCount} requests",
            formatUsd(group.costUsd),
            group.unknownCostCount.takeIf { it > 0 && group.costUsd != null }?.let { "$it cost unknown" },
        ).joinToString(" · ")
    val list =
        makeListGroup(context).apply {
            group.records.forEach { record ->
                val model = record.model.takeIf(String::isNotBlank) ?: "Model unavailable"
                val details =
                    listOfNotNull(
                        AiUsagePlanning.recordTokens(record)?.let { "$it tokens" },
                        formatRequestTime(record.timestamp, REQUEST_DETAIL_TIME_FORMATTER),
                        singleOutcomeLabel(record.outcome),
                    ).joinToString(" · ")
                val cost =
                    makeText(context, formatUsd(record.costUsd), Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(context.dp(Space.SM), 0, context.dp(Space.SM), 0)
                    }
                addView(makeListRow(context, model, details, trailing = cost))
            }
        }
    showBottomSheet(context, title = title, subtitle = subtitle, body = list)
}

private fun featureLabel(feature: String): String =
    when (feature) {
        "description" -> "Description"
        "cover_selection" -> "Automatic cover selection"
        "description_selection" -> "Automatic description selection"
        "cover_prompt" -> "Cover prompt"
        "cover_image" -> "Cover image"
        "characters" -> "Character list"
        "portrait_selection" -> "Portrait passage search"
        "portrait_prompt" -> "Portrait prompt"
        "portrait_image" -> "Portrait image"
        else -> feature.takeIf(String::isNotBlank) ?: "AI request"
    }

private fun singleOutcomeLabel(outcome: String): String? =
    when (outcome) {
        "completed" -> null
        "empty" -> "Empty reply"
        else -> outcome.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: "Unknown"
    }

private fun groupOutcomeLabel(records: List<AiUsageRecord>): String? {
    if (records.size == 1) return singleOutcomeLabel(records.first().outcome)
    val nonCompleted =
        records
            .filter { it.outcome != "completed" }
            .groupingBy(AiUsageRecord::outcome)
            .eachCount()
    if (nonCompleted.isEmpty()) return null
    return nonCompleted.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { (outcome, count) ->
            val label =
                when (outcome) {
                    "empty" -> "empty"
                    else -> outcome.takeIf(String::isNotBlank)?.replace('_', ' ') ?: "unknown"
                }
            "$count $label"
        }
}

private fun formatRequestTime(
    timestamp: Long,
    formatter: DateTimeFormatter = REQUEST_TIME_FORMATTER,
): String =
    runCatching {
        formatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault("Unknown time")

private val REQUEST_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

private val REQUEST_DETAIL_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.getDefault())

