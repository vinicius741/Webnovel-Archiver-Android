package com.vinicius741.webnovelarchiver

import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import com.vinicius741.webnovelarchiver.core.SettingsValidation
import com.vinicius741.webnovelarchiver.core.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.TabPlanning
import com.vinicius741.webnovelarchiver.core.settingsMaxWidth
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*
import java.util.UUID

internal fun ScreenHost.showSettings() {
    val displayPreferences = storage.getDisplayPreferences()
    // Re-render so toggling "Large Screen Layout" / width changes re-centers the capped content live.
    rerender = { showSettings() }
    val layout = currentScreenLayout()
    screen(title = "Settings", onBack = { showLibrary() }, scrollable = true) {
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
        // Controls how the app treats the screen on foldables/large displays. "Auto" detects the fold
        // sensor + window size, "Cover" forces a single-column phone layout, "Inner" forces the
        // multi-column tablet layout. This is the native equivalent of the RN app's FoldLayoutMode.
        text(
            "How multi-column layouts behave on large/folded screens. Auto detects the display.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.XS)
        flow(spacing = Space.MD) {
            chip("Auto", displayPreferences.screenLayoutMode == "auto") {
                storage.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "auto"))
                showSettings()
            }
            chip("Cover", displayPreferences.screenLayoutMode == "cover") {
                storage.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "cover"))
                showSettings()
            }
            chip("Inner", displayPreferences.screenLayoutMode == "inner") {
                storage.saveDisplayPreferences(displayPreferences.copy(screenLayoutMode = "inner"))
                showSettings()
            }
        }
        spacer(Space.MD)
        text("EPUB Volume Folding", Type.TITLE_SMALL)
        // S6: explain what EPUB volume folding controls. Renamed from "Fold Layout" to avoid confusion
        // with the screen-fold "Large Screen Layout" setting above — this one is about EPUB structure.
        text("How chapters fold inside EPUB volumes. Auto picks based on length.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        spacer(Space.XS)
        flow(spacing = Space.MD) {
            chip("Auto", displayPreferences.foldLayoutMode == "auto") {
                storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "auto"))
                showSettings()
            }
            chip("Cover", displayPreferences.foldLayoutMode == "cover") {
                storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "cover"))
                showSettings()
            }
            chip("Inner", displayPreferences.foldLayoutMode == "inner") {
                storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "inner"))
                showSettings()
            }
        }
        divider()
        section("Downloads")
        settingRow(R.drawable.wna_list, "Download Manager", "View and manage active downloads") { showQueue() }
        settingRow(R.drawable.wna_download, "Download Settings", "Concurrency, delay, and EPUB volume size") { showDownloadSettings() }
        settingRow(R.drawable.wna_globe, "Source Overrides", "Per-source concurrency and delay") { showSourceOverrides() }
        divider()
        section("Text To Speech")
        settingRow(R.drawable.wna_speaker, "Voice & Speech", "Pitch, rate, chunk size, and voice") { showTtsSettings() }
        divider()
        section("Library Organization")
        settingRow(R.drawable.wna_folder, "Manage Tabs", "Create and organize custom tabs for your library") { showTabs() }
        divider()
        section("Data")
        settingRow(R.drawable.wna_cleaning, "Text Cleanup Rules", "Manage sentence removal and regex cleanup rules") { showCleanupRules() }
        settingRow(R.drawable.wna_delete, "Clear Local Storage", "Delete all novels and reset app data") {
            confirm("Delete all novels, settings, and downloads?", confirmLabel = "Delete") {
                storage.clearAll()
                showLibrary()
            }
        }
        divider()
        section("Backup")
        settingRow(R.drawable.wna_share, "Export Backup", "Export library metadata and tabs to a JSON file") {
            exportAndShare { storage.exportBackup() }
        }
        settingRow(R.drawable.wna_download, "Import Backup", "Merge novels and tabs from a JSON backup file") {
            importBackupLauncher.launch(arrayOf("application/json", "text/*"))
        }
        settingRow(R.drawable.wna_archive, "Create Full Backup", "Save settings, tabs, library, and chapters to a local ZIP file") {
            exportAndShare { storage.exportFullBackup() }
        }
        settingRow(R.drawable.wna_archive, "Restore Full Backup", "Replace local data from a full ZIP backup") {
            importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
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
        if (layout.widthClass != com.vinicius741.webnovelarchiver.core.WidthClass.COMPACT) {
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

/** Download tuning sub-screen (concurrency / delay / max chapters per EPUB), reached from Settings. */
internal fun ScreenHost.showDownloadSettings() {
    val settings = storage.getSettings()
    screen(title = "Download Settings", onBack = { showSettings() }, scrollable = true) {
        val concurrency = labeledField("Concurrency", settings.downloadConcurrency.toString(), InputType.TYPE_CLASS_NUMBER)
        val delay = labeledField("Delay (ms)", settings.downloadDelay.toString(), InputType.TYPE_CLASS_NUMBER)
        val maxChapters = labeledField("Max chapters per EPUB", settings.maxChaptersPerEpub.toString(), InputType.TYPE_CLASS_NUMBER)
        fullButton("Save Downloads", Btn.FILLED, R.drawable.wna_check, topMarginDp = Space.LG, bottomMarginDp = Space.SM) {
            storage.saveSettings(
                settings.copy(
                    downloadConcurrency = SettingsValidation.concurrency(concurrency.text.toString(), settings.downloadConcurrency),
                    downloadDelay = SettingsValidation.delay(delay.text.toString(), settings.downloadDelay),
                    maxChaptersPerEpub = SettingsValidation.maxChaptersPerEpub(maxChapters.text.toString(), settings.maxChaptersPerEpub),
                ),
            )
            toast("Download settings saved")
        }
    }
}

/** Per-source override sub-screen (one card per registered provider), reached from Settings. */
internal fun ScreenHost.showSourceOverrides() {
    val settings = storage.getSettings()
    val sourceSettings = storage.getSourceDownloadSettings()
    screen(title = "Source Overrides", onBack = { showSettings() }, scrollable = true) {
        val sourceInputs =
            SourceRegistry.all().associate { provider ->
                val override = sourceSettings[provider.name]
                val sourceEnabled =
                    CheckBox(context).apply {
                        text = "Override"
                        isChecked = override != null
                    }
                var sourceConcurrency: EditText? = null
                var sourceDelay: EditText? = null
                addView(
                    card {
                        text(provider.name, Type.TITLE_SMALL)
                        styledCheckBox(sourceEnabled)
                        addView(sourceEnabled)
                        // S3: the card is already titled with the provider name, so the inner labels repeat it.
                        sourceConcurrency =
                            labeledField(
                                "Concurrency",
                                (override?.concurrency ?: settings.downloadConcurrency).toString(),
                                InputType.TYPE_CLASS_NUMBER,
                            )
                        sourceDelay =
                            labeledField("Delay (ms)", (override?.delay ?: settings.downloadDelay).toString(), InputType.TYPE_CLASS_NUMBER)
                        flow {
                            button("Reset", Btn.TEXT, R.drawable.wna_refresh) {
                                val updated = storage.getSourceDownloadSettings().toMutableMap()
                                updated.remove(provider.name)
                                storage.saveSourceDownloadSettings(updated)
                                showSourceOverrides()
                            }
                        }
                    },
                )
                provider.name to Triple(sourceEnabled, sourceConcurrency!!, sourceDelay!!)
            }
        // S1: Source Overrides save independently of Downloads / TTS.
        fullButton("Save Overrides", Btn.FILLED, R.drawable.wna_check, bottomMarginDp = Space.SM) {
            storage.saveSourceDownloadSettings(
                sourceInputs
                    .mapNotNull { (name, inputs) ->
                        if (!inputs.first.isChecked) {
                            null
                        } else {
                            name to
                                SourceDownloadSettings(
                                    concurrency =
                                        SettingsValidation.concurrency(
                                            inputs.second.text.toString(),
                                            settings.downloadConcurrency,
                                        ),
                                    delay = SettingsValidation.delay(inputs.third.text.toString(), settings.downloadDelay),
                                )
                        }
                    }.toMap(),
            )
            toast("Source overrides saved")
        }
    }
}

/** TTS sub-screen (pitch / rate / chunk size / voice + optional saved-session card), reached from Settings. */
internal fun ScreenHost.showTtsSettings() {
    val ttsSettings = storage.getTtsSettings()
    screen(title = "Voice & Speech", onBack = { showSettings() }, scrollable = true) {
        storage.getTtsSession()?.let { session ->
            addView(
                card {
                    text("Saved TTS session", Type.TITLE_SMALL)
                    text(
                        "${session.chapterTitle} (chunk ${session.currentChunkIndex + 1})",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    )
                    flow {
                        button("Resume TTS", Btn.TONAL, R.drawable.wna_play) {
                            TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
                        }
                        button("Clear Session", Btn.TEXT, R.drawable.wna_delete) {
                            storage.clearTtsSession()
                            showTtsSettings()
                        }
                    }
                },
            )
        }
        val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val chunkSize = labeledField("Chunk size", ttsSettings.chunkSize.toString(), InputType.TYPE_CLASS_NUMBER)
        // S4: the voice label is itself the control — tap it to open the picker.
        val voiceLabel = ttsSettings.voiceIdentifier ?: "System default"
        row {
            addView(
                makeText(context, "Voice", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val voiceBtn = makeButton(context, voiceLabel, Btn.TEXT, R.drawable.wna_speaker) { showTtsVoicePicker() }
            addView(
                voiceBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(Space.MD)
                },
            )
        }
        fullButton("Save TTS", Btn.FILLED, R.drawable.wna_check, bottomMarginDp = Space.SM) {
            storage.saveTtsSettings(
                ttsSettings.copy(
                    pitch = SettingsValidation.ttsScalar(pitch.text.toString(), ttsSettings.pitch),
                    rate = SettingsValidation.ttsScalar(rate.text.toString(), ttsSettings.rate),
                    chunkSize = SettingsValidation.ttsChunkSize(chunkSize.text.toString(), ttsSettings.chunkSize),
                ),
            )
            toast("TTS settings saved")
        }
    }
}

internal fun ScreenHost.showTtsVoicePicker() {
    val voices = ttsEngine.availableVoices()
    if (voices.isEmpty()) {
        toast("No local TTS voices available yet")
        return
    }
    showTtsVoiceDialog(voices, storage.getTtsSettings().voiceIdentifier) { voice ->
        val current = storage.getTtsSettings()
        storage.saveTtsSettings(current.copy(voiceIdentifier = voice?.identifier))
        showTtsSettings()
    }
}

internal fun ScreenHost.saveThemePreference(themeId: String) {
    val current = storage.getDisplayPreferences()
    storage.saveDisplayPreferences(current.copy(activeThemeId = themeId))
    applyThemePreference(themeId)
    app.recreate()
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

internal fun ScreenHost.showTabs() {
    screen(title = "Manage Tabs", onBack = { showSettings() }, scrollable = true) {
        val tabs = TabPlanning.normalizeOrders(storage.getTabs())
        // T1: explain what tabs are so the empty/first-run state isn't a bare input.
        text(
            "Tabs group novels on the Library screen. Create one (e.g. \"Reading\", \"Finished\") and assign novels to it.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        row {
            val name = makeField(context, "", "New tab name", InputType.TYPE_CLASS_TEXT)
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.TONAL, R.drawable.wna_add) {
                val next =
                    TabPlanning.create(
                        tabs,
                        name.text.toString(),
                        "tab_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                        System.currentTimeMillis(),
                    )
                if (next.size > tabs.size) {
                    storage.saveTabs(next)
                    showTabs()
                }
            }
        }
        tabs.forEachIndexed { index, tab ->
            val novelCount = storage.getLibrary().count { it.tabId == tab.id }
            addView(
                card {
                    row {
                        addView(
                            ImageView(context).apply {
                                setImageDrawable(context.tintedIcon(R.drawable.wna_folder, ThemeManager.colors.primary))
                                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                            },
                        )
                        addView(
                            makeText(context, tab.name, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                                setPadding(dp(10), 0, 0, 0)
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        // T3: add a noun so the bare count reads as a count, not a stray number.
                        val novelLabel = if (novelCount == 1) "novel" else "novels"
                        addView(makeText(context, "$novelCount $novelLabel", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant))
                    }
                    flow {
                        button("Up", Btn.TEXT, R.drawable.wna_up) {
                            if (index > 0) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index - 1))
                                showTabs()
                            }
                        }
                        button("Down", Btn.TEXT, R.drawable.wna_down) {
                            if (index < tabs.lastIndex) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index + 1))
                                showTabs()
                            }
                        }
                        button("Rename", Btn.TEXT, R.drawable.wna_edit) {
                            prompt("Rename Tab", tab.name) {
                                storage.saveTabs(TabPlanning.rename(tabs, tab.id, it))
                                showTabs()
                            }
                        }
                        // T2: Delete uses the error variant so it reads as destructive, not routine.
                        button("Delete", Btn.ERROR, R.drawable.wna_delete) {
                            confirm("Delete tab \"${tab.name}\" and move its novels to Unassigned?", confirmLabel = "Delete") {
                                storage.getLibrary().forEach { story ->
                                    if (story.tabId == tab.id) {
                                        story.tabId = null
                                        storage.addOrUpdateStory(story)
                                    }
                                }
                                storage.saveTabs(TabPlanning.delete(tabs, tab.id))
                                showTabs()
                            }
                        }
                    }
                },
            )
        }
    }
}
