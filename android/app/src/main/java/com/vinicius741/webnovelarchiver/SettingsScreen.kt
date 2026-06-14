package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.graphics.Color
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
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*
import java.util.UUID

internal fun ScreenHost.showSettings() {
    val settings = storage.getSettings()
    val sourceSettings = storage.getSourceDownloadSettings()
    val ttsSettings = storage.getTtsSettings()
    val displayPreferences = storage.getDisplayPreferences()
    screen(title = "Settings", onBack = { showLibrary() }, scrollable = true) {
        section("Appearance")
        text("Theme", Type.TITLE_SMALL)
        flow {
            Themes.all.forEach { theme ->
                chip(theme.name, displayPreferences.activeThemeId == theme.id) { saveThemePreference(theme.id) }
            }
        }
        text("Active: ${displayPreferences.activeThemeId}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        text("Fold Layout", Type.TITLE_SMALL)
        flow {
            chip("Auto", displayPreferences.foldLayoutMode == "auto") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "auto")); showSettings() }
            chip("Cover", displayPreferences.foldLayoutMode == "cover") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "cover")); showSettings() }
            chip("Inner", displayPreferences.foldLayoutMode == "inner") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "inner")); showSettings() }
        }
        divider()
        section("Downloads")
        val concurrency = labeledField("Concurrency", settings.downloadConcurrency.toString(), InputType.TYPE_CLASS_NUMBER)
        val delay = labeledField("Delay (ms)", settings.downloadDelay.toString(), InputType.TYPE_CLASS_NUMBER)
        val maxChapters = labeledField("Max chapters per EPUB", settings.maxChaptersPerEpub.toString(), InputType.TYPE_CLASS_NUMBER)
        divider()
        section("Source Overrides")
        val sourceInputs = SourceRegistry.all().associate { provider ->
            val override = sourceSettings[provider.name]
            val sourceEnabled = CheckBox(context).apply { text = "Override ${provider.name}"; isChecked = override != null }
            var sourceConcurrency: EditText? = null
            var sourceDelay: EditText? = null
            addView(card {
                text(provider.name, Type.TITLE_SMALL)
                styledCheckBox(sourceEnabled)
                addView(sourceEnabled)
                sourceConcurrency = labeledField("${provider.name} concurrency", (override?.concurrency ?: settings.downloadConcurrency).toString(), InputType.TYPE_CLASS_NUMBER)
                sourceDelay = labeledField("${provider.name} delay (ms)", (override?.delay ?: settings.downloadDelay).toString(), InputType.TYPE_CLASS_NUMBER)
                flow {
                    button("Reset ${provider.name}", Btn.TEXT, R.drawable.wna_refresh) {
                        val updated = storage.getSourceDownloadSettings().toMutableMap()
                        updated.remove(provider.name)
                        storage.saveSourceDownloadSettings(updated)
                        showSettings()
                    }
                }
            })
            provider.name to Triple(sourceEnabled, sourceConcurrency!!, sourceDelay!!)
        }
        divider()
        section("Text To Speech")
        storage.getTtsSession()?.let { session ->
            addView(card {
                text("Saved TTS session", Type.TITLE_SMALL)
                text("${session.chapterTitle} (chunk ${session.currentChunkIndex + 1})", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                flow {
                    button("Resume TTS", Btn.TONAL, R.drawable.wna_play) {
                        TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
                    }
                    button("Clear Session", Btn.TEXT, R.drawable.wna_delete) {
                        storage.clearTtsSession()
                        showSettings()
                    }
                }
            })
        }
        val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val chunkSize = labeledField("Chunk size", ttsSettings.chunkSize.toString(), InputType.TYPE_CLASS_NUMBER)
        text("Voice: ${ttsSettings.voiceIdentifier ?: "System default"}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        flow {
            button("Save", Btn.FILLED, R.drawable.wna_check) {
                storage.saveSettings(settings.copy(
                    downloadConcurrency = SettingsValidation.concurrency(concurrency.text.toString(), settings.downloadConcurrency),
                    downloadDelay = SettingsValidation.delay(delay.text.toString(), settings.downloadDelay),
                    maxChaptersPerEpub = SettingsValidation.maxChaptersPerEpub(maxChapters.text.toString(), settings.maxChaptersPerEpub),
                ))
                storage.saveSourceDownloadSettings(sourceInputs.mapNotNull { (name, inputs) ->
                    if (!inputs.first.isChecked) {
                        null
                    } else {
                        name to SourceDownloadSettings(
                            concurrency = SettingsValidation.concurrency(inputs.second.text.toString(), settings.downloadConcurrency),
                            delay = SettingsValidation.delay(inputs.third.text.toString(), settings.downloadDelay),
                        )
                    }
                }.toMap())
                storage.saveTtsSettings(ttsSettings.copy(
                    pitch = SettingsValidation.ttsScalar(pitch.text.toString(), ttsSettings.pitch),
                    rate = SettingsValidation.ttsScalar(rate.text.toString(), ttsSettings.rate),
                    chunkSize = SettingsValidation.ttsChunkSize(chunkSize.text.toString(), ttsSettings.chunkSize),
                ))
                toast("Settings saved")
                showSettings()
            }
            button("Voice", Btn.TONAL, R.drawable.wna_speaker) { showTtsVoicePicker() }
            button("Manage Tabs", Btn.TEXT, R.drawable.wna_folder) { showTabs() }
            button("Cleanup Rules", Btn.TEXT, R.drawable.wna_brush) { showCleanupRules() }
        }
        divider()
        section("Backup")
        flow {
            button("Export JSON", Btn.TONAL, R.drawable.wna_share) { exportAndShare { storage.exportBackup() } }
            button("Import JSON", Btn.TEXT, R.drawable.wna_download) { importBackupLauncher.launch(arrayOf("application/json", "text/*")) }
            button("Full Backup", Btn.TONAL, R.drawable.wna_share) { exportAndShare { storage.exportFullBackup() } }
            button("Restore Full", Btn.TEXT, R.drawable.wna_download) { importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        flow {
            button("Clear Local Storage", Btn.ERROR, R.drawable.wna_delete) { confirm("Delete all novels, settings, and downloads?") { storage.clearAll(); showLibrary() } }
        }
    }
}

internal fun ScreenHost.showTtsVoicePicker() {
    val voices = ttsEngine.availableVoices()
    if (voices.isEmpty()) {
        toast("No local TTS voices available yet")
        return
    }
    val labels = listOf("System default") + voices.map { "${it.name} (${it.language})" }
    AlertDialog.Builder(app)
        .setTitle("TTS Voice")
        .setItems(labels.toTypedArray()) { _, which ->
            val current = storage.getTtsSettings()
            storage.saveTtsSettings(current.copy(voiceIdentifier = voices.getOrNull(which - 1)?.identifier))
            showSettings()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.saveThemePreference(themeId: String) {
    val current = storage.getDisplayPreferences()
    storage.saveDisplayPreferences(current.copy(activeThemeId = themeId))
    applyThemePreference(themeId)
    app.recreate()
}

internal fun ScreenHost.applyThemePreference(themeId: String) {
    ThemeManager.apply(themeId)
    val nightMode = when (themeId) {
        "classic-light" -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }
    AppCompatDelegate.setDefaultNightMode(nightMode)
}

internal fun ScreenHost.showTabs() {
    screen(title = "Manage Tabs", onBack = { showSettings() }, scrollable = true) {
        val tabs = TabPlanning.normalizeOrders(storage.getTabs())
        row {
            val name = EditText(context).apply {
                hint = "New tab name"
                setBackgroundColor(Color.TRANSPARENT)
                setHintTextColor(ThemeManager.colors.onSurfaceVariant)
                setTextColor(ThemeManager.colors.onSurface)
                setSingleLine()
            }
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.TONAL, R.drawable.wna_add) {
                val next = TabPlanning.create(
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
            addView(card {
                row {
                    addView(ImageView(context).apply {
                        setImageDrawable(context.tintedIcon(R.drawable.wna_folder, ThemeManager.colors.primary))
                        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    })
                    addView(makeText(context, tab.name, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(makeText(context, "${storage.getLibrary().count { it.tabId == tab.id }}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant))
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
                    button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                        confirm("Delete tab \"${tab.name}\" and move its novels to Unassigned?") {
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
            })
        }
    }
}
