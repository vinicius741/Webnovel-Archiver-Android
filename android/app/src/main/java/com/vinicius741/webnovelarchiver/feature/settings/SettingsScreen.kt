package com.vinicius741.webnovelarchiver.feature.settings

import android.content.Context
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
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Screen-layout modes in the order the segmented control shows them. */
private val LAYOUT_MODES = listOf("auto" to "Auto", "cover" to "Cover", "inner" to "Inner")

/** The hub's reads, from the repository, the narration store and Android. */
private class HostSettingsHubData(
    private val host: ScreenHost,
) : SettingsHubData {
    private val repository get() = host.repository

    override suspend fun summaries(): HubSummaries =
        withContext(Dispatchers.IO) { HubSummaries(voiceSummary(), patreonAccountSummary(), aiSummary()) }

    override suspend fun phoneSummary(): PhoneSummary =
        withContext(Dispatchers.IO) {
            val library = repository.library()
            val audio = runCatching { host.narration.store.totalBytes() }.getOrDefault(0L)
            val newest = runCatching { repository.listBackupFiles() }.getOrDefault(emptyList()).maxByOrNull { it.lastModifiedMillis }
            PhoneSummary(library.size, library.sumOf { it.downloadedChapters }, audio, newest?.lastModifiedMillis, newest?.sizeBytes)
        }

    override suspend fun audioBytes(): Long = withContext(Dispatchers.IO) { host.narration.store.totalBytes() }

    override val compression: Flow<NarrationCompressionStatus> get() = host.narration.compression

    override fun rows(): SettingsHubRows {
        val settings = UnsavedSettings.downloads.shown { repository.settings.get() }
        val updates = host.app.appContainer.appUpdates
        return SettingsHubRows(
            cleanup = SettingsHubPlanning.cleanupSummary(repository.regexRules.get().size, repository.sentenceRemovalList.get().size),
            tabs = SettingsHubPlanning.tabsSummary(TabPlanning.normalizeOrders(repository.tabs.get()).map { it.name }),
            downloads =
                SettingsHubPlanning.downloadsSummary(
                    settings.maxParallelSources ?: DEFAULT_PARALLEL_SOURCES,
                    PaceRange(settings.downloadDelay, maxOf(settings.downloadDelay, settings.downloadDelayMax)),
                ),
            screenLayoutMode = repository.getDisplayPreferences().screenLayoutMode,
            notificationsOff = notificationsSummary(),
            storageNeedsAttention = repository.getStorageHealth().requiresUserAttention,
            appUpdate = appUpdateSummary(updates),
            appUpdateNeedsAttention = appUpdateNeedsAttention(updates),
        )
    }

    override suspend fun saveScreenLayoutMode(mode: String) =
        repository.saveDisplayPreferences(repository.getDisplayPreferences().copy(screenLayoutMode = mode))

    private fun voiceSummary(): String {
        val app = host.app
        val tts = repository.ttsSettings.get()
        val engine = TtsEnginePlanning.label(tts.enginePackage, TtsEngineCatalog.choices(app), TtsEngineCatalog.phoneDefault(app))
        return SettingsHubPlanning.voiceSummary(engine, ttsVoiceLabel(tts.voiceIdentifier, tts.enginePackage), tts.rate)
    }

    private fun aiSummary(): String {
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
    private fun notificationsSummary(): String? {
        if (!AppNotificationChannels.hasPostNotificationsPermission(host.app)) return "Off · download progress is hidden. Tap to allow"
        if (!AppNotificationChannels.areAppNotificationsEnabled(host.app)) return "Blocked in Android settings"
        return null
    }
}

/** The last loaded hub values, shown at once on the next visit while fresh ones load. */
private val hubCache = SettingsHubCache()

/** The hub's model, kept while Settings is in the history. */
private fun ScreenHost.settingsModel(): SettingsScreenModel =
    screenModels.get(AppRoute.Settings.stableKey) { SettingsScreenModel(HostSettingsHubData(this), hubCache) }

/** Re-reads the hub's values when it is on screen (returning from Android's notification settings). */
internal fun ScreenHost.refreshSettingsHub() {
    screenModels.find<SettingsScreenModel>(AppRoute.Settings.stableKey)?.refresh()
}

/**
 * The Settings destination (v3): a summary of what is on this phone, then grouped rows (Listening,
 * Reading & display, Library, Intelligence, System), each with its current value, and the app version.
 * Built from [SettingsScreenModel]'s state: a row value change rebuilds the page, and the "On this
 * phone" card follows the rest in place.
 */
internal fun ScreenHost.showSettings() {
    val colors = ThemeManager.colors
    screen(route = AppRoute.Settings, title = "Settings", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = settingsModel()
        model.refresh()
        val state = model.state.value
        val rows = state.rows
        val summaries = state.summaries
        capSettingsWidth(this)
        val phoneCard = PhoneSummaryCard(this)
        phoneCard.show(state)

        section("Listening")
        settingsGroup {
            navRow(R.drawable.wna_record_voice, "Listening", summaries?.voice ?: LOADING) { showTtsSettings() }
        }

        section("Reading & display")
        settingsGroup {
            navRow(R.drawable.wna_cleaning, "Text cleanup", rows.cleanup) { showCleanupRules() }
            // Only a large (inner) screen has a choice to make; a non-default mode stays reachable to undo.
            val largeScreen = foldTracker.isFoldingFeature.value || app.resources.configuration.screenWidthDp >= LARGE_SCREEN_DP
            if (largeScreen || rows.screenLayoutMode != LAYOUT_MODES.first().first) {
                addView(layoutModeRow(context, rows.screenLayoutMode) { mode -> model.setScreenLayoutMode(mode) })
            }
        }

        section("Library")
        settingsGroup {
            navRow(R.drawable.wna_tab, "Manage tabs", rows.tabs) { showTabs() }
            navRow(R.drawable.wna_download, "Downloads", rows.downloads) { showDownloadSettings() }
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
            val notificationsOff = rows.notificationsOff
            navRow(
                R.drawable.wna_notifications,
                "Notifications",
                notificationsOff ?: "Allowed · Android settings",
                tint = if (notificationsOff != null) colors.error else colors.primary,
                chevron = false,
            ) { performNotificationPermissionAction() }
            val attention = rows.storageNeedsAttention
            navRow(
                R.drawable.wna_backup,
                "Data & backup",
                if (attention) "Storage needs attention" else "Backup, restore, troubleshooting",
                tint = if (attention) colors.error else colors.primary,
            ) {
                showDataBackup()
            }
            val updateTint = if (rows.appUpdateNeedsAttention) colors.tertiary else colors.primary
            navRow(R.drawable.wna_system_update, "App updates", rows.appUpdate, tint = updateTint) { showAppUpdate() }
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
        bindWhileAttached(scope, model.state) { next ->
            if (next.buildKey != state.buildKey) showSettings() else phoneCard.show(next)
        }
    }
}

/**
 * "On this phone": novels, chapters offline, generated audio (with the background compression of
 * older audio while it runs), and the newest backup file.
 */
private class PhoneSummaryCard(
    container: LinearLayout,
) {
    private lateinit var values: List<TextView>
    private lateinit var backupLine: TextView
    private val compression = CompressionStatusView(container.context)

    init {
        container.tintedCard(ThemeManager.colors.primary) {
            addView(makeEyebrow(context, "On this phone"))
            values = statGrid(listOf("novels", "chapters offline", "audio"))
            addView(
                compression.view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Spacing.MD)
                },
            )
            backupLine =
                makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, context.dp(Spacing.SM + 2), 0, 0)
                }
            addView(backupLine)
        }
    }

    fun show(state: SettingsHubUiState) {
        state.phone?.let { summary ->
            val numbers = NumberFormat.getIntegerInstance()
            values[0].text = numbers.format(summary.novels)
            values[1].text = numbers.format(summary.chapters)
            values[2].text = NarrationPlanning.formatBytes(summary.audioBytes)
            backupLine.text =
                summary.lastBackupAt?.let { at ->
                    "Last backup ${BackupFilePlanning.timestampLabel(at)} · ${BackupFilePlanning.sizeLabel(summary.lastBackupBytes ?: 0L)}"
                } ?: "No backup files yet"
        }
        val total = state.audioBytes ?: return
        values[2].text = NarrationPlanning.formatBytes(total)
        state.compression?.let { compression.bind(it, total) }
    }
}

/** Auto / Cover / Inner: how multi-column layouts behave on the inner screen. */
private fun layoutModeRow(
    app: Context,
    mode: String,
    onSelect: (String) -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val selected = LAYOUT_MODES.indexOfFirst { it.first == mode }.coerceAtLeast(0)
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(app.dp(Spacing.LG), app.dp(Spacing.MD), app.dp(Spacing.LG), app.dp(Spacing.MD))
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_smartphone, colors.primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = roundedBg(colors.elevation3, app.dp(LEAD_RADIUS_DP).toFloat())
            },
            LinearLayout.LayoutParams(app.dp(LEAD_DP), app.dp(LEAD_DP)).apply { marginEnd = app.dp(Spacing.MD + 2) },
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
                    ).apply { setPadding(0, app.dp(2), 0, 0) },
                )
                val control = SegmentedSelector(app, LAYOUT_MODES.map { it.second }, selected) { index -> onSelect(LAYOUT_MODES[index].first) }
                addView(
                    control.view,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = app.dp(Spacing.SM + 2)
                    },
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}

internal const val DEFAULT_PARALLEL_SOURCES = 2
private const val LOADING = "…"
private const val LARGE_SCREEN_DP = 600
private const val FOOTER_LINE_SPACING = 1.4f
private const val LEAD_DP = 40
private const val LEAD_RADIUS_DP = 12
private const val ROW_TITLE_SP = 15f
