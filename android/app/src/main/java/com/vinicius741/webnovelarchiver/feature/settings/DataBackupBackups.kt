package com.vinicius741.webnovelarchiver.feature.settings

import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.repository.BackupFileEntry
import com.vinicius741.webnovelarchiver.data.repository.deleteBackupFile
import com.vinicius741.webnovelarchiver.data.repository.listBackupFiles
import com.vinicius741.webnovelarchiver.feature.story.share
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportKind
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Full-backup loop for Data & Backup: the export job owner, the live progress card, and the
 * persistent Backup Files list that makes completed backups reachable without the share sheet.
 */

internal fun ScreenHost.startFullBackupExport() {
    val state = backupExportState
    if (state.activeKind != null) return
    // The job is assigned before the flag: reconcile() would clear a flag whose job is not yet live.
    state.activeJob =
        scope.launch {
            try {
                val backup =
                    runCatching { repository.exportFullBackup { message -> updateBackupProgress(message) } }
                        .getOrElse { error ->
                            toast(error.message ?: "Backup failed")
                            return@launch
                        }
                showStyledOptionsDialog(
                    "Backup saved · ${BackupFilePlanning.sizeLabel(backup.length())}",
                    listOf(
                        "Share backup file" to { share(backup) },
                        "Done" to {},
                    ),
                )
            } finally {
                state.activeKind = null
                state.progressMessage = null
                state.progressSlot = null
                if (navigator.current == AppRoute.DataBackup && !app.isDestroyed) showDataBackup()
            }
        }
    state.activeKind = BackupExportKind.FULL
    if (navigator.current == AppRoute.DataBackup) showDataBackup()
}

/** Posts a progress tick onto the UI thread and patches the visible card without a re-render. */
private fun ScreenHost.updateBackupProgress(message: String) {
    app.runOnUiThread {
        backupExportState.progressMessage = message
        backupExportState.progressSlot?.text = message
    }
}

internal fun ScreenHost.addFullBackupProgressCard(container: LinearLayout) {
    val state = backupExportState
    if (state.activeKind != BackupExportKind.FULL) return
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            row {
                addView(
                    makeText(context, "Creating full backup…", Type.TITLE_SMALL, colors.onSurface),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            spacer(Space.XS)
            state.progressSlot =
                text(state.progressMessage ?: "Preparing…", Type.BODY_SMALL, colors.onSurfaceVariant)
        }
    container.addView(cardView)
}

internal fun ScreenHost.addBackupFilesSection(container: LinearLayout) {
    val colors = ThemeManager.colors
    container.section("Backup Files")
    val filesContainer =
        LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            text("Loading backup files…", Type.BODY_SMALL, colors.onSurfaceVariant)
        }
    container.addView(filesContainer)
    scope.launch {
        val result = runCatching { repository.listBackupFiles() }
        if (navigator.current != AppRoute.DataBackup || app.isDestroyed || !filesContainer.isAttachedToWindow) return@launch
        filesContainer.removeAllViews()
        result
            .onSuccess { files -> renderBackupFiles(filesContainer, files) }
            .onFailure {
                filesContainer.text("Backup files could not be loaded.", Type.BODY_SMALL, colors.onSurfaceVariant)
            }
    }
}

private fun ScreenHost.renderBackupFiles(
    container: LinearLayout,
    files: List<BackupFileEntry>,
) {
    val colors = ThemeManager.colors
    if (files.isEmpty()) {
        container.text(
            "Backups you create are kept here, inside the app's private storage.",
            Type.BODY_SMALL,
            colors.onSurfaceVariant,
        )
        return
    }
    files.forEach { file -> addBackupFileCard(container, file) }
}

private fun ScreenHost.addBackupFileCard(
    container: LinearLayout,
    entry: BackupFileEntry,
) {
    val file = entry.file
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            row {
                addView(
                    makeText(
                        context,
                        BackupFilePlanning.artifactLabel(file.name),
                        Type.TITLE_SMALL,
                        colors.onSurface,
                    ),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            text(
                "${BackupFilePlanning.sizeLabel(entry.sizeBytes)} · " +
                    BackupFilePlanning.timestampLabel(entry.lastModifiedMillis),
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, 0, 0, dp(Space.SM)) }
            row {
                button("Share", Btn.TONAL, R.drawable.wna_share) { share(file) }
                button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                    confirm(
                        "Delete this backup file from the app's storage?",
                        confirmLabel = "Delete",
                    ) {
                        scope.launch {
                            if (repository.deleteBackupFile(file)) {
                                showDataBackup()
                            } else {
                                toast("Backup file could not be deleted")
                            }
                        }
                    }
                }
            }
        }
    container.addView(cardView)
}
