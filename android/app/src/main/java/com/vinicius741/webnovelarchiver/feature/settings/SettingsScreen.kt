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
import com.vinicius741.webnovelarchiver.feature.narration.CompressionStatusView
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
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
 * The Settings destination (v3): a summary of what is on this phone, then grouped rows (Listening,
 * Reading & display, Library, Intelligence, System), each with its current value, and the app version.
 */
internal fun ScreenHost.showSettings() {
    // Re-render so layout/width toggles re-center the capped content live.
    rerender = { showSettings() }
    val colors = ThemeManager.colors
    val summaries = lastHubSummaries
    screen(route = AppRoute.Settings, title = "Settings", scrollable = true) {
        capSettingsWidth(this)
        addPhoneSummaryCard(this)

        section("Listening")
        settingsGroup {
            navRow(R.drawable.wna_record_voice, "Listening", summaries?.voice ?: LOADING) { showTtsSettings() }
        }

        section("Reading & display")
        settingsGroup {
            val cleanup = SettingsHubPlanning.cleanupSummary(repository.regexRules.get().size, repository.sentenceRemovalList.get().size)
            navRow(R.drawable.wna_cleaning, "Text cleanup", cleanup) { showCleanupRules() }
            // Only a large (inner) screen has a choice to make; a non-default mode stays reachable to undo.
            val largeScreen = foldTracker.isFoldingFeature.value || app.resources.configuration.screenWidthDp >= LARGE_SCREEN_DP
            if (largeScreen || repository.getDisplayPreferences().screenLayoutMode != LAYOUT_MODES.first().first) {
                addView(layoutModeRow())
            }
        }

        section("Library")
        settingsGroup {
            val tabs = TabPlanning.normalizeOrders(repository.tabs.get())
            navRow(R.drawable.wna_tab, "Manage tabs", SettingsHubPlanning.tabsSummary(tabs.map { it.name })) { showTabs() }
            val settings = downloadSettingsShown()
            val downloads =
                SettingsHubPlanning.downloadsSummary(
                    settings.maxParallelSources ?: DEFAULT_PARALLEL_SOURCES,
                    PaceRange(settings.downloadDelay, maxOf(settings.downloadDelay, settings.downloadDelayMax)),
                )
            navRow(R.drawable.wna_download, "Downloads", downloads) { showDownloadSettings() }
            navRow(
                R.drawable.wna_favorite,
                "Patreon account",
                summaries?.patreon ?: LOADING,
                tint = colors.secondary,
            ) { showPatreonAccount() }
        }

        section("Intelligence")
        settingsGroup {
            navRow(R.drawable.wna_auto_awesome, "AI settings", summaries?.ai ?: LOADING, tint = colors.secondary) { showAiSettings() }
        }

        section("System")
        settingsGroup {
            val notificationsOff = notificationsSummary()
            navRow(
                R.drawable.wna_notifications,
                "Notifications",
                notificationsOff ?: "Allowed · Android settings",
                tint = if (notificationsOff != null) colors.error else colors.primary,
                chevron = false,
            ) { performNotificationPermissionAction() }
            val storageHealth = repository.getStorageHealth()
            val dataSummary = if (storageHealth.requiresUserAttention) "Storage needs attention" else "Backup, restore, troubleshooting"
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
    refreshHubSummaries(summaries)
}

/** The hub values that need PackageManager, WebView cookie or disk work, so they load off the main thread. */
private data class HubSummaries(
    val voice: String,
    val patreon: String,
    val ai: String,
)

/** The last loaded values, shown at once on the next visit while fresh ones load. */
private var lastHubSummaries: HubSummaries? = null

/** Loads the hub values on IO; the hub re-renders only when they differ from the ones [shown]. */
private fun ScreenHost.refreshHubSummaries(shown: HubSummaries?) {
    scope.launch {
        val fresh = withContext(Dispatchers.IO) { HubSummaries(voiceSummary(), patreonAccountSummary(), aiSummary()) }
        lastHubSummaries = fresh
        if (fresh != shown && navigator.current == AppRoute.Settings) showSettings()
    }
}

/**
 * "On this phone": novels, chapters offline, generated audio (with the background compression of
 * older audio while it runs), and the newest backup file.
 */
private fun ScreenHost.addPhoneSummaryCard(container: LinearLayout) {
    lateinit var values: List<TextView>
    lateinit var backupLine: TextView
    val compression = CompressionStatusView(app)

    fun fill(summary: PhoneSummary) {
        val numbers = NumberFormat.getIntegerInstance()
        values[0].text = numbers.format(summary.novels)
        values[1].text = numbers.format(summary.chapters)
        values[2].text = NarrationPlanning.formatBytes(summary.audioBytes)
        backupLine.text =
            summary.lastBackupAt?.let { at ->
                "Last backup ${BackupFilePlanning.timestampLabel(at)} · ${BackupFilePlanning.sizeLabel(summary.lastBackupBytes ?: 0L)}"
            } ?: "No backup files yet"
    }
    container.tintedCard(ThemeManager.colors.primary) {
        addView(makeEyebrow(context, "On this phone"))
        values = statGrid(listOf("novels", "chapters offline", "audio"))
        addView(
            compression.view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Spacing.MD)
            },
        )
        backupLine =
            makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(Spacing.SM + 2), 0, 0)
            }
        addView(backupLine)
    }
    // A re-render (fold, or fresh hub values) shows the last numbers instead of blanking them.
    lastPhoneSummary?.let(::fill)
    scope.launch {
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
        lastPhoneSummary = summary
        fill(summary)
    }
    // Each converted chapter shrinks the audio: the stat and the status line follow it live.
    val store = narration.store
    screenObserver =
        scope.launch {
            narration.compression.collect { status ->
                val total = withContext(Dispatchers.IO) { store.totalBytes() }
                values[2].text = NarrationPlanning.formatBytes(total)
                lastPhoneSummary = lastPhoneSummary?.copy(audioBytes = total)
                compression.bind(status, total)
            }
        }
}

private var lastPhoneSummary: PhoneSummary? = null

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
    return SettingsHubPlanning.voiceSummary(engine, ttsVoiceLabel(tts.voiceIdentifier, tts.enginePackage), tts.rate)
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

/** Why notifications are off, or null when Android allows them. */
private fun ScreenHost.notificationsSummary(): String? {
    if (!AppNotificationChannels.hasPostNotificationsPermission(app)) return "Off · download progress is hidden. Tap to allow"
    if (!AppNotificationChannels.areAppNotificationsEnabled(app)) return "Blocked in Android settings"
    return null
}

internal const val DEFAULT_PARALLEL_SOURCES = 2
private const val LOADING = "…"
private const val LARGE_SCREEN_DP = 600
private const val FOOTER_LINE_SPACING = 1.4f
private const val LEAD_DP = 40
private const val LEAD_RADIUS_DP = 12
private const val ROW_TITLE_SP = 15f
