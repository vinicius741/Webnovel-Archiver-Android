package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassLogExporter
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.feature.cleanup.showCleanupRules
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.library.showLibrarySelection
import com.vinicius741.webnovelarchiver.feature.settings.SettingsValidation
import com.vinicius741.webnovelarchiver.feature.story.exportAndShare
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportKind
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Themes
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.divider
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.labeledField
import com.vinicius741.webnovelarchiver.ui.layout.settingsMaxWidth
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.settingRow
import com.vinicius741.webnovelarchiver.ui.settingRowWithLoading
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

private data class SourceDownloadInputs(
    val enabled: CheckBox,
    val delayMin: EditText,
    val delayMax: EditText,
)

internal fun ScreenHost.showSettings() {
    val displayPreferences = repository.getDisplayPreferences()
    // Re-render so toggling "Large Screen Layout" / width changes re-centers the capped content live.
    rerender = { showSettings() }
    val layout = currentScreenLayout()
    screen(route = AppRoute.Settings, title = "Settings", onBack = { showLibrary() }, scrollable = true) {
        section("Appearance")
        text("Theme", Type.TITLE_SMALL)
        spacer(Space.XS)
        flow(spacing = Space.MD) {
            Themes.all.forEach { theme ->
                chip(theme.name, displayPreferences.activeThemeId == theme.id) { saveThemePreference(theme.id) }
            }
        }
        spacer(Space.MD)
        text("Large Screen Layout", Type.TITLE_SMALL)
        text(
            "How multi-column layouts behave on large/folded screens. Auto detects the display.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.XS)
        flow(spacing = Space.MD) {
            chip("Auto", displayPreferences.screenLayoutMode == "auto") {
                scope.launch {
                    repository.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "auto"))
                    showSettings()
                }
            }
            chip("Cover", displayPreferences.screenLayoutMode == "cover") {
                scope.launch {
                    repository.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "cover"))
                    showSettings()
                }
            }
            chip("Inner", displayPreferences.screenLayoutMode == "inner") {
                scope.launch {
                    repository.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "inner"))
                    showSettings()
                }
            }
        }
        // EPUB volume splitting is controlled per-story / Download Settings ("Max chapters per EPUB"),
        // not a global Cover/Inner toggle. foldLayoutMode remains in DisplayPreferences for backup
        // compatibility but is intentionally not exposed here — nothing in the EPUB engine reads it.
        divider()
        section("Reading & Audio")
        settingRow(R.drawable.wna_speaker, "Voice & Speech", "Pitch, rate, and voice") { showTtsSettings() }
        settingRow(R.drawable.wna_cleaning, "Text Cleanup Rules", "Manage sentence removal and regex cleanup rules") { showCleanupRules() }
        settingRow(R.drawable.wna_auto_awesome, "AI Settings", "OpenRouter API key and models for AI descriptions") { showAiSettings() }
        divider()
        // Operational destinations live off the Library top bar (Downloads) or are promoted here as
        // ungrouped rows without their own noisy single-row section header.
        settingRow(R.drawable.wna_notifications, "Notifications", "Manage downloads and text-to-speech alerts") {
            showNotifications()
        }
        settingRow(R.drawable.wna_tab, "Manage Tabs", "Create and organize custom tabs for your library") { showTabs() }
        settingRow(R.drawable.wna_check, "Organize Novels", "Select, move, or delete novels in your library") {
            showLibrarySelection()
        }
        // Storage issues surface as a marker on the title so the row still draws attention without
        // permanently occupying its own slot in the main list.
        val storageHealth = repository.getStorageHealth()
        val dataBackupTitle = if (storageHealth.requiresUserAttention) "Data & Backup •" else "Data & Backup"
        settingRow(R.drawable.wna_folder, dataBackupTitle, "Backups, source access, and storage tools") {
            showDataBackup()
        }
        // Width cap: on large screens, constrain this content LinearLayout and center it within the
        // ScrollView so Settings doesn't stretch edge-to-edge (expanded → 840dp, medium → 720dp).
        // No-op on compact widths where the cap exceeds the screen.
        //
        // This block runs inside `screen(...)`'s content builder, where `this` is the content
        // LinearLayout *before* it is added to its ScrollView parent. At that point the view has no
        // layout params yet (layoutParams == null), so reading/casting them NPEs. We instead set fresh
        // FrameLayout.LayoutParams (the ScrollView is a FrameLayout) with a fixed width and centered
        // gravity; the scaffold assigns exactly these params when it wraps content in the ScrollView.
        if (layout.widthClass != com.vinicius741.webnovelarchiver.ui.layout.WidthClass.COMPACT) {
            val contentMaxWidthDp = settingsMaxWidth(layout.widthClass)
            layoutParams =
                android.widget.FrameLayout.LayoutParams(
                    context.dp(contentMaxWidthDp),
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_HORIZONTAL,
                )
        }
    }
}

/**
 * Aggregated home for the heavy, rarely-used action rows that would clutter the main Settings
 * screen: backup/restore (JSON + ZIP), source-session maintenance, storage notices, and
 * destructive reset.
 */
internal fun ScreenHost.showDataBackup() {
    backupExportState.reconcile()
    screen(route = AppRoute.DataBackup, title = "Data & Backup", onBack = { showSettings() }, scrollable = true) {
        section("Backup")
        // The host-owned state prevents concurrent exports and survives configuration re-renders.
        // Each controller is captured via a nullable holder so the click lambda can update its row.
        var exportRow: com.vinicius741.webnovelarchiver.ui.SettingActionRow? = null
        settingRowWithLoading(
            R.drawable.wna_share,
            "Export Backup",
            "Export library metadata and tabs to a JSON file",
            loading = backupExportState.activeKind == BackupExportKind.JSON,
        ) {
            if (backupExportState.activeKind == null) {
                backupExportState.activeKind = BackupExportKind.JSON
                exportRow?.render(isLoading = true)
                backupExportState.activeJob =
                    exportAndShare({ repository.exportBackup() }) {
                        backupExportState.activeKind = null
                        if (navigator.current == AppRoute.DataBackup) showDataBackup()
                    }
            }
        }.also { exportRow = it }
        settingRow(R.drawable.wna_download, "Import Backup", "Merge novels and tabs from a JSON backup file") {
            importBackupLauncher.launch(arrayOf("application/json", "text/*"))
        }
        settingRowWithLoading(
            R.drawable.wna_archive,
            "Create Full Backup",
            "Save settings, tabs, library, and chapters to a local ZIP file",
            loading = backupExportState.activeKind == BackupExportKind.FULL,
        ) {
            startFullBackupExport()
        }
        addFullBackupProgressCard(this)
        settingRow(R.drawable.wna_unarchive, "Restore Full Backup", "Replace local data from a full ZIP backup") {
            importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        addBackupFilesSection(this)
        divider()
        section("Sources & Storage")
        val storageHealth = repository.getStorageHealth()
        if (storageHealth.requiresUserAttention) {
            settingRow(
                R.drawable.wna_archive,
                "Storage recovery notice",
                "${storageHealth.issues.size} storage issue(s) detected; preserved data was not overwritten",
            ) {
                toast(storageHealth.issues.joinToString("\n") { issue -> "${issue.document}: ${issue.detail}" })
            }
        }
        val sourceNetwork = app.appContainer.network
        val browserSessionSources = SourceRegistry.all().filter { it.descriptor.managesBrowserSession }
        val sourceSnapshots =
            sourceNetwork
                .reliabilitySnapshots()
                .filter { snapshot ->
                    SourceRegistry.providerForHost(snapshot.host)?.descriptor?.managesBrowserSession == true
                }
        val webViewPackage = WebView.getCurrentWebViewPackage()
        val sourceAccessSummary =
            when {
                sourceSnapshots.any { it.manualVerificationRequired } -> "Verification required"
                sourceSnapshots.any { it.browserTransportActive } -> "Chromium transport active"
                sourceSnapshots.any { it.cooldownRemainingMillis > 0L } -> "Cooling down"
                else -> "Ready"
            } + " • WebView ${webViewPackage?.versionName ?: "unavailable"}"
        settingRow(R.drawable.wna_globe, "Source Access Status", sourceAccessSummary) {
            val detail =
                if (sourceSnapshots.isEmpty()) {
                    "No protected-source requests in this app session"
                } else {
                    sourceSnapshots.joinToString("\n") { snapshot ->
                        "${snapshot.host}: requests ${snapshot.requestCount} • challenges ${snapshot.challengeCount} • " +
                            "rate limits ${snapshot.rateLimitCount} • browser pages ${snapshot.browserRenderCount}"
                    }
                }
            toast(detail)
        }
        settingRow(
            R.drawable.wna_share,
            "Share Source Access Logs",
            "Export a diagnostic log of source-access events (no URLs, titles, or cookies)",
        ) {
            exportAndShare({ BypassLogExporter.export(app.appContainer.repository.storage.backupRoot, sourceNetwork, repository.queue()) })
        }
        settingRow(R.drawable.wna_cleaning, "Reset Source Web Session", "Clear source cookies, browser storage, and access cooldowns") {
            confirm("Reset source browser sessions? The next request may require verification.", confirmLabel = "Reset") {
                resetSourceWebSessions(browserSessionSources)
            }
        }
        divider()
        section("Danger Zone")
        settingRow(R.drawable.wna_delete, "Clear Local Storage", "Delete all novels and reset app data") {
            confirm("Delete all novels, settings, and downloads?", confirmLabel = "Delete") {
                scope.launch {
                    repository.clearAll()
                    showLibrary()
                }
            }
        }
    }
}

/** Download settings sub-screen: parallel source lanes plus per-source delay overrides. */
internal fun ScreenHost.showDownloadSettings() {
    val settings = repository.getSettings()
    val sourceSettings = repository.getSourceDownloadSettings()
    screen(route = AppRoute.DownloadSettings, title = "Download Settings", onBack = { showSettings() }, scrollable = true) {
        // Parallelism is across independent sources. Each source itself owns one sequential request
        // lane, with the delay defaults below applied between starts in that lane.
        section("Defaults")
        text(
            "Download from different sources together. Requests to the same source stay sequential and use its own delay.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.XS)
        // Group the three number fields in one card so they read as a unit and don't sit bare on the
        // background next to the carded source overrides below. `labeledField` adds itself to the card
        // receiver and returns the EditText; capture via nullable vars (same pattern as the source cards).
        var parallelSources: EditText? = null
        var delayMin: EditText? = null
        var delayMax: EditText? = null
        var maxChapters: EditText? = null
        addView(
            card {
                parallelSources =
                    labeledField(
                        "Parallel sources",
                        (settings.maxParallelSources ?: 2).toString(),
                        InputType.TYPE_CLASS_NUMBER,
                    )
                spacer(Space.SM)
                delayMin = labeledField("Delay min (ms)", settings.downloadDelay.toString(), InputType.TYPE_CLASS_NUMBER)
                spacer(Space.SM)
                delayMax = labeledField("Delay max (ms)", settings.downloadDelayMax.toString(), InputType.TYPE_CLASS_NUMBER)
                spacer(Space.SM)
                maxChapters = labeledField("Max chapters per EPUB", settings.maxChaptersPerEpub.toString(), InputType.TYPE_CLASS_NUMBER)
            },
        )

        // Per-source overrides affect pacing only. Source concurrency is deliberately fixed at one;
        // throughput comes from independent sources rather than parallel requests to one website.
        section("Source Delay Overrides")
        text(
            "Replace the delay defaults for a specific source. Fields appear when you enable an override.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.XS)
        val sourceInputs =
            SourceRegistry.all().associate { provider ->
                val override = sourceSettings[provider.id] ?: sourceSettings[provider.name]
                var toggle: CheckBox? = null
                var sourceDelayMin: EditText? = null
                var sourceDelayMax: EditText? = null
                // Build the whole card in one block so the row + fields end up as children of the card,
                // not the screen content. The DSL `row`/`labeledField` helpers add themselves to whatever
                // ViewGroup is the receiver, so they must be called inside `card { }`.
                addView(
                    card {
                        row {
                            addView(
                                makeText(context, provider.name, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface),
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                            )
                            // No switch component exists; the themed checkbox acts as the per-source toggle
                            // (no label — the card title covers it).
                            val cb =
                                CheckBox(context).apply {
                                    text = ""
                                    isChecked = override != null
                                }
                            styledCheckBox(cb)
                            addView(cb)
                            toggle = cb
                        }
                        val fieldsContainer =
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                visibility = if (override != null) View.VISIBLE else View.GONE
                            }
                        fieldsContainer.apply {
                            sourceDelayMin =
                                labeledField(
                                    "Delay min (ms)",
                                    (override?.delay ?: settings.downloadDelay).toString(),
                                    InputType.TYPE_CLASS_NUMBER,
                                )
                            sourceDelayMax =
                                labeledField(
                                    "Delay max (ms)",
                                    (override?.delayMax ?: settings.downloadDelayMax).toString(),
                                    InputType.TYPE_CLASS_NUMBER,
                                )
                        }
                        addView(fieldsContainer)
                        // Toggling just reveals/hides the fields without re-rendering, so values the user
                        // typed are preserved while the box is checked.
                        toggle!!.setOnCheckedChangeListener { _, isChecked ->
                            fieldsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                        }
                    },
                )
                provider.id to SourceDownloadInputs(toggle!!, sourceDelayMin!!, sourceDelayMax!!)
            }

        fullButton("Save", Btn.FILLED, R.drawable.wna_check, topMarginDp = Space.LG, bottomMarginDp = Space.SM) {
            val delayRange =
                SettingsValidation.delayRange(
                    delayMin!!.text.toString(),
                    delayMax!!.text.toString(),
                    settings.downloadDelay,
                    settings.downloadDelayMax,
                )
            val updatedSettings =
                settings.copy(
                    maxParallelSources =
                        SettingsValidation.concurrency(
                            parallelSources!!.text.toString(),
                            settings.maxParallelSources ?: 2,
                        ),
                    downloadDelay = delayRange.first,
                    downloadDelayMax = delayRange.second,
                    maxChaptersPerEpub = SettingsValidation.maxChaptersPerEpub(maxChapters!!.text.toString(), settings.maxChaptersPerEpub),
                )
            val updatedSourceSettings =
                sourceInputs
                    .mapNotNull { (name, inputs) ->
                        if (!inputs.enabled.isChecked) {
                            null
                        } else {
                            val sourceDelayRange =
                                SettingsValidation.delayRange(
                                    inputs.delayMin.text.toString(),
                                    inputs.delayMax.text.toString(),
                                    settings.downloadDelay,
                                    settings.downloadDelayMax,
                                )
                            name to
                                SourceDownloadSettings(
                                    concurrency = 1,
                                    delay = sourceDelayRange.first,
                                    delayMax = sourceDelayRange.second,
                                )
                        }
                    }.toMap()
            scope.launch {
                repository.saveSettings(updatedSettings)
                repository.saveSourceDownloadSettings(updatedSourceSettings)
                toast("Download settings saved")
            }
        }
    }
}

internal fun ScreenHost.saveThemePreference(themeId: String) {
    val current = repository.getDisplayPreferences()
    scope.launch {
        repository.saveDisplayPreferences(current.copy(activeThemeId = themeId))
        applyThemePreference(themeId)
        app.recreate()
    }
}

internal fun ScreenHost.applyThemePreference(themeId: String) {
    ThemeManager.apply(themeId)
    val nightMode =
        when (themeId) {
            "classic-light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
    AppCompatDelegate.setDefaultNightMode(nightMode)
}
