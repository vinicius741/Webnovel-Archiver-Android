package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.BackupFileEntry
import com.vinicius741.webnovelarchiver.data.repository.deleteBackupFile
import com.vinicius741.webnovelarchiver.data.repository.listBackupFiles
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.alert
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import java.io.File

/** Data & backup's reads and changes, from the repository. */
private class HostDataBackupData(
    private val host: ScreenHost,
) : DataBackupData {
    override suspend fun backupFiles(): List<BackupFileEntry> = host.repository.listBackupFiles()

    override suspend fun deleteBackupFile(file: File): Boolean = host.repository.deleteBackupFile(file)

    override fun storageIssues(): List<String> =
        host.repository
            .getStorageHealth()
            .issues
            .map { issue -> "${issue.document}: ${issue.detail}" }

    override suspend fun clearAll() = host.repository.clearAll()
}

/** Data & backup's model, kept while it is in the history. */
private fun ScreenHost.dataBackupModel(): DataBackupScreenModel =
    screenModels.get(AppRoute.DataBackup.stableKey) { DataBackupScreenModel(HostDataBackupData(this), backupExports) }

/**
 * Backup and restore, the backup files kept on this phone, troubleshooting tools, and the reset,
 * built from [DataBackupScreenModel]'s state. The export's row, its progress card and the backup
 * files follow the state in place; a change in storage problems rebuilds the page.
 */
internal fun ScreenHost.showDataBackup() {
    val colors = ThemeManager.colors
    screen(route = AppRoute.DataBackup, title = "Data & backup", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = dataBackupModel()
        model.refresh()
        val state = model.state.value
        capSettingsWidth(this)
        section("Backup")
        lateinit var exportRow: LoadingRow
        settingsGroup {
            exportRow =
                loadingRow(
                    R.drawable.wna_archive,
                    "Create backup",
                    "Everything, including chapters",
                    state.export != null,
                ) {
                    model.startExport()
                }
            navRow(R.drawable.wna_restore, "Restore backup", "Replace this library with a backup", chevron = false) {
                importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        val progress = FullBackupProgressCard(this)
        progress.show(state.export)
        val files = BackupFilesSection(this@showDataBackup, this, model)
        files.show(state.files)

        section("Troubleshooting")
        settingsGroup { addTroubleshootingRows(this, state.storageIssues) }

        section("Danger zone").setTextColor(colors.error)
        tintedCard(colors.error) {
            iconColumnRow(R.drawable.wna_delete, colors.error).apply {
                addView(makeText(context, "Clear local storage", Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP })
                addView(
                    makeText(
                        context,
                        "Deletes every novel, chapter and setting on this phone. Backups are kept.",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply {
                        setPadding(0, dp(2), 0, 0)
                    },
                )
            }
            addView(
                makeButton(context, "Clear local storage", Btn.ERROR) {
                    confirm(
                        "Delete all novels, settings, and downloads? Backups on this phone are kept.",
                        confirmLabel = "Delete everything",
                    ) {
                        model.clearLocalStorage { showLibrary() }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.MD)
                },
            )
        }
        var applied = state
        bindWhileAttached(scope, model.state) { next ->
            if (next.buildKey != applied.buildKey) {
                showDataBackup()
                return@bindWhileAttached
            }
            exportRow.setLoading(next.export != null)
            progress.show(next.export)
            if (next.files != applied.files) files.show(next.files)
            applied = next
        }
    }
}

private fun ScreenHost.addTroubleshootingRows(
    group: LinearLayout,
    storageIssues: List<String>,
) {
    val colors = ThemeManager.colors
    if (storageIssues.isNotEmpty()) {
        group.navRow(
            R.drawable.wna_error,
            "Storage recovery notice",
            "${storageIssues.size} storage issue(s); preserved data was not overwritten",
            tint = colors.error,
            chevron = false,
        ) {
            alert("Storage recovery notice", storageIssues.joinToString("\n"))
        }
    }
    group.navRow(R.drawable.wna_bug_report, "Share diagnostics", "Recent activity and source access; a copy goes to Downloads") {
        shareDiagnostics()
    }
    group.navRow(R.drawable.wna_restore, "Reset all source sessions", "Clears every site's cookies, browser storage and cooldowns") {
        confirm("Reset every source's browser session? The next request may need a quick check again.", confirmLabel = "Reset") {
            resetSourceWebSessions(SourceRegistry.web().filter { it.descriptor.managesBrowserSession })
        }
    }
}

/** A list row whose leading icon turns into a spinner while its job runs. */
internal class LoadingRow(
    val view: LinearLayout,
    private val icon: ImageView,
    private val spinner: ProgressBar,
) {
    fun setLoading(loading: Boolean) {
        icon.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        view.isEnabled = !loading
        view.alpha = if (loading) LOADING_ALPHA else 1f
    }
}

internal fun LinearLayout.loadingRow(
    icon: Int,
    title: String,
    supporting: String,
    loading: Boolean,
    onClick: () -> Unit,
): LoadingRow {
    val colors = ThemeManager.colors
    val iconView =
        ImageView(context).apply {
            setImageDrawable(context.tintedIcon(icon, colors.primary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    val spinner =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(colors.primary)
        }
    val lead =
        FrameLayout(context).apply {
            background = roundedBg(colors.elevation3, context.dp(LEAD_RADIUS_DP).toFloat())
            addView(iconView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val pad = context.dp(SPINNER_PAD_DP)
            addView(
                spinner,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    setMargins(pad, pad, pad, pad)
                },
            )
            layoutParams = LinearLayout.LayoutParams(context.dp(LEAD_DP), context.dp(LEAD_DP))
        }
    lateinit var handle: LoadingRow
    val row = makeListRow(context, title, supporting, lead = lead) { if (handle.view.isEnabled) onClick() }
    handle = LoadingRow(row, iconView, spinner)
    handle.setLoading(loading)
    addView(row)
    return handle
}

private const val TITLE_SP = 15f
private const val LEAD_DP = 40
private const val LEAD_RADIUS_DP = 12
private const val SPINNER_PAD_DP = 9
private const val LOADING_ALPHA = 0.6f
