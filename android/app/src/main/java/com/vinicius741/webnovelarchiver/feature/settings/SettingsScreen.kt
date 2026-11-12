package com.vinicius741.webnovelarchiver.feature.settings

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiUsagePeriod
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.listBackupFiles
import com.vinicius741.webnovelarchiver.feature.cleanup.showCleanupRules
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.notification.NotificationCategoryStatus
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Screen-layout modes in the order the segmented control shows them. */
private val LAYOUT_MODES = listOf("auto" to "Auto", "cover" to "Cover", "inner" to "Inner")

/**
 * The Settings destination (v3): a summary of what is on this phone, then grouped rows (Listening
 * & reading, Library, Intelligence, System), each with its current value, and the app version.
 */
internal fun ScreenHost.showSettings() {
    // Re-render so layout/width toggles re-center the capped content live.
    rerender = { showSettings() }
    val colors = ThemeManager.colors
    screen(route = AppRoute.Settings, title = "Settings", scrollable = true) {
        capSettingsWidth(this)
        addPhoneSummaryCard(this)

        section("Listening & reading")
        settingsGroup {
            navRow(R.drawable.wna_record_voice, "Voice & Speech", voiceSummary()) { showTtsSettings() }
            val cleanup = SettingsHubPlanning.cleanupSummary(repository.regexRules.get().size, repository.sentenceRemovalList.get().size)
            navRow(R.drawable.wna_cleaning, "Text cleanup", cleanup) { showCleanupRules() }
        }

        section("Library")
        settingsGroup {
            val tabs = TabPlanning.normalizeOrders(repository.tabs.get())
            navRow(R.drawable.wna_tab, "Manage tabs", SettingsHubPlanning.tabsSummary(tabs.map { it.name })) { showTabs() }
            val settings = repository.settings.get()
            val downloads =
                SettingsHubPlanning.downloadsSummary(
                    settings.maxParallelSources ?: DEFAULT_PARALLEL_SOURCES,
                    PaceRange(settings.downloadDelay, maxOf(settings.downloadDelay, settings.downloadDelayMax)),
                )
            navRow(R.drawable.wna_download, "Downloads", downloads) { showDownloadSettings() }
            addView(layoutModeRow())
            navRow(R.drawable.wna_favorite, "Patreon account", patreonAccountSummary(), tint = colors.secondary) { showPatreonAccount() }
        }

        section("Intelligence")
        settingsGroup {
            navRow(R.drawable.wna_auto_awesome, "AI settings", aiSummary(), tint = colors.secondary) { showAiSettings() }
        }

        section("System")
        settingsGroup {
            navRow(R.drawable.wna_notifications, "Notifications", notificationsSummary()) { showNotifications() }
            val storageHealth = repository.getStorageHealth()
            val dataSummary = if (storageHealth.requiresUserAttention) "Storage needs attention" else "Backups, sources, storage"
            navRow(
                R.drawable.wna_backup,
                "Data & backup",
                dataSummary,
                tint = if (storageHealth.requiresUserAttention) colors.error else colors.primary,
            ) {
                showDataBackup()
            }
            val updates = app.appContainer.appUpdates
            val updateTint = if (appUpdateNeedsAttention(updates)) colors.tertiary else colors.primary
            navRow(R.drawable.wna_system_update, "App updates", appUpdateSummary(updates), tint = updateTint) { showAppUpdate() }
        }

        addView(
            makeText(
                context,
                "Webnovel Archiver ${BuildConfig.VERSION_NAME}\nMade for offline reading and listening",
                Type.BODY_SMALL,
                colors.faint,
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, FOOTER_LINE_SPACING)
                setPadding(0, dp(Spacing.XL + 4), 0, dp(Spacing.SM))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }
}

/** "On this phone": novels, chapters offline, generated audio, and the newest backup file. */
private fun ScreenHost.addPhoneSummaryCard(container: LinearLayout) {
    lateinit var values: List<TextView>
    lateinit var backupLine: TextView
    container.tintedCard(ThemeManager.colors.primary) {
        addView(makeEyebrow(context, "On this phone"))
        values = statGrid(listOf("novels", "chapters offline", "audio"))
        backupLine =
            makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(Spacing.SM + 2), 0, 0)
            }
        addView(backupLine)
    }
    scope.launch {
        val numbers = NumberFormat.getIntegerInstance()
        val summary =
            withContext(Dispatchers.IO) {
                val library = repository.library()
                val audio =
                    runCatching {
                        app.appContainer.narration.store
                            .totalBytes()
                    }.getOrDefault(0L)
                val newest = runCatching { repository.listBackupFiles() }.getOrDefault(emptyList()).maxByOrNull { it.lastModifiedMillis }
                PhoneSummary(library.size, library.sumOf { it.downloadedChapters }, audio, newest?.lastModifiedMillis, newest?.sizeBytes)
            }
        values[0].text = numbers.format(summary.novels)
        values[1].text = numbers.format(summary.chapters)
        values[2].text = NarrationPlanning.formatBytes(summary.audioBytes)
        backupLine.text =
            summary.lastBackupAt?.let { at ->
                "Last backup ${BackupFilePlanning.timestampLabel(at)} · ${BackupFilePlanning.sizeLabel(summary.lastBackupBytes ?: 0L)}"
            } ?: "No backup files yet"
    }
}

private data class PhoneSummary(
    val novels: Int,
    val chapters: Int,
    val audioBytes: Long,
    val lastBackupAt: Long?,
    val lastBackupBytes: Long?,
)

/** Auto / Cover / Inner: how multi-column layouts behave on the inner screen. */
private fun ScreenHost.layoutModeRow(): LinearLayout {
    val colors = ThemeManager.colors
    val display = repository.getDisplayPreferences()
    val selected = LAYOUT_MODES.indexOfFirst { it.first == display.screenLayoutMode }.coerceAtLeast(0)
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(Spacing.LG), dp(Spacing.MD), dp(Spacing.LG), dp(Spacing.MD))
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_smartphone, colors.primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = roundedBg(colors.elevation3, dp(LEAD_RADIUS_DP).toFloat())
            },
            LinearLayout.LayoutParams(dp(LEAD_DP), dp(LEAD_DP)).apply { marginEnd = dp(Spacing.MD + 2) },
        )
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(app, "Large screen layout", Type.TITLE_SMALL, colors.onSurface).apply { textSize = ROW_TITLE_SP })
                addView(
                    makeText(
                        app,
                        "How the inner screen is used",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply { setPadding(0, dp(2), 0, 0) },
                )
                val control =
                    SegmentedSelector(app, LAYOUT_MODES.map { it.second }, selected) { index ->
                        scope.launch {
                            repository.saveDisplayPreferences(
                                repository.getDisplayPreferences().copy(screenLayoutMode = LAYOUT_MODES[index].first),
                            )
                            showSettings()
                        }
                    }
                addView(
                    control.view,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Spacing.SM + 2)
                    },
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}

private fun ScreenHost.voiceSummary(): String {
    val tts = repository.ttsSettings.get()
    val engine = TtsEnginePlanning.label(tts.enginePackage, TtsEngineCatalog.choices(app), TtsEngineCatalog.phoneDefault(app))
    return SettingsHubPlanning.voiceSummary(engine, ttsVoiceLabel(tts.voiceIdentifier), tts.rate)
}

private fun ScreenHost.aiSummary(): String {
    if (repository.aiSettings
            .get()
            .apiKey
            .isNullOrBlank()
    ) {
        return "Add an OpenRouter key"
    }
    val month = AiUsagePlanning.summaryForPeriod(repository.getAiUsageLedger(), AiUsagePeriod.CURRENT_MONTH, System.currentTimeMillis())
    return "OpenRouter · ${formatUsd(month.costUsd)} this month"
}

private fun ScreenHost.notificationsSummary(): String {
    if (!AppNotificationChannels.hasPostNotificationsPermission(app)) return "Not allowed for downloads"
    if (!AppNotificationChannels.areAppNotificationsEnabled(app)) return "Blocked in Android settings"
    val on = AppNotificationCategory.entries.count { AppNotificationChannels.status(app, it) == NotificationCategoryStatus.ENABLED }
    return "Allowed · $on of ${AppNotificationCategory.entries.size} categories on"
}

internal const val DEFAULT_PARALLEL_SOURCES = 2
private const val FOOTER_LINE_SPACING = 1.4f
private const val LEAD_DP = 40
private const val LEAD_RADIUS_DP = 12
private const val ROW_TITLE_SP = 15f
