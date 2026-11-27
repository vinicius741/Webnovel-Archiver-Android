package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.repository.BackupFileEntry
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.feature.story.share
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import java.io.File

/*
 * Data & backup's export pieces: the app side of [BackupExports], the live progress card, and the
 * persistent Backup files list that makes completed backups reachable without the share sheet.
 */

/** The export runs the repository's full or quick backup; its outcome is shown on whatever screen is open. */
internal class HostBackupExportHost(
    private val host: ScreenHost,
) : BackupExportHost {
    override suspend fun export(
        kind: BackupKind,
        onProgress: (String) -> Unit,
    ): File =
        when (kind) {
            BackupKind.Full -> host.repository.exportFullBackup(onProgress)
            BackupKind.Quick -> host.repository.exportQuickBackup(includeCovers = false, onProgress)
            BackupKind.QuickWithCovers -> host.repository.exportQuickBackup(includeCovers = true, onProgress)
        }

    override fun showSaved(file: File) {
        if (host.app.isDestroyed) return
        host.showStyledOptionsDialog(
            "Backup saved · ${BackupFilePlanning.sizeLabel(file.length())}",
            listOf(
                "Share backup file" to { host.share(file) },
                "Done" to {},
            ),
        )
    }

    override fun showFailed(message: String) = host.toast(message)
}

/** The "Creating … backup…" card, shown while an export runs; a new progress line patches its text. */
internal class FullBackupProgressCard(
    container: LinearLayout,
) {
    private val slot = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL }
    private var message: TextView? = null

    init {
        container.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun show(export: BackupExportProgress?) {
        if (export == null) {
            slot.removeAllViews()
            message = null
            return
        }
        val text = export.message ?: "Preparing…"
        message?.let {
            it.text = text
            return
        }
        val colors = ThemeManager.colors
        slot.tintedCard(colors.primary) {
            iconColumnRow(R.drawable.wna_archive, colors.primary).apply {
                val title = if (export.kind == BackupKind.Full) "Creating full backup…" else "Creating quick backup…"
                addView(makeText(context, title, Type.TITLE_SMALL, colors.onSurface))
                message =
                    makeText(context, text, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                        setPadding(0, context.dp(2), 0, 0)
                    }
                addView(message)
            }
            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(colors.primary)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.SM)
                },
            )
        }
    }
}

/** The Backup files section; [show] redraws its list. */
internal class BackupFilesSection(
    private val host: ScreenHost,
    container: LinearLayout,
    private val model: DataBackupScreenModel,
) {
    private val files: LinearLayout

    init {
        container.section("Backup files")
        files = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(files)
    }

    fun show(state: BackupFilesState) {
        val colors = ThemeManager.colors
        files.removeAllViews()
        when (state) {
            BackupFilesState.Loading -> files.text("Loading backup files…", Type.BODY_SMALL, colors.onSurfaceVariant)
            BackupFilesState.Failed -> files.text("Backup files could not be loaded.", Type.BODY_SMALL, colors.onSurfaceVariant)
            is BackupFilesState.Loaded -> renderBackupFiles(state.files)
        }
    }

    private fun renderBackupFiles(entries: List<BackupFileEntry>) {
        val colors = ThemeManager.colors
        if (entries.isEmpty()) {
            files.settingsNote("Backups you create are kept here, in the app's private storage.")
            return
        }
        files.settingsGroup {
            entries.forEach { entry ->
                val file = entry.file
                val actions =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            makeRowIconButton(
                                context,
                                R.drawable.wna_share,
                                "Share ${BackupFilePlanning.artifactLabel(file.name)}",
                            ) { host.share(file) },
                        )
                        addView(
                            makeRowIconButton(context, R.drawable.wna_delete, "Delete ${BackupFilePlanning.artifactLabel(file.name)}") {
                                host.confirm("Delete this backup file? This cannot be undone.", confirmLabel = "Delete") {
                                    model.deleteBackupFile(file) { host.toast("Backup file could not be deleted") }
                                }
                            },
                        )
                    }
                addView(
                    makeListRow(
                        context,
                        BackupFilePlanning.artifactLabel(file.name),
                        "${BackupFilePlanning.sizeLabel(entry.sizeBytes)} · ${BackupFilePlanning.timestampLabel(entry.lastModifiedMillis)}",
                        leadIcon = R.drawable.wna_folder,
                        leadTint = colors.primary,
                        trailing = actions,
                    ),
                )
            }
        }
    }
}
