package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogExporter
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassLogExporter
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.feature.story.exportAndShare
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportKind
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/** Backups and restore, the backup files kept on this phone, source-access tools, and the reset. */
internal fun ScreenHost.showDataBackup() {
    backupExportState.reconcile()
    val colors = ThemeManager.colors
    screen(route = AppRoute.DataBackup, title = "Data & backup", scrollable = true) {
        capSettingsWidth(this)
        section("Backup")
        settingsGroup {
            lateinit var exportRow: LoadingRow
            exportRow =
                loadingRow(
                    R.drawable.wna_upload,
                    "Export backup",
                    "Library, tabs and settings as JSON",
                    backupExportState.activeKind == BackupExportKind.JSON,
                ) {
                    if (backupExportState.activeKind == null) {
                        backupExportState.activeKind = BackupExportKind.JSON
                        exportRow.setLoading(true)
                        backupExportState.activeJob =
                            exportAndShare({ repository.exportBackup() }) {
                                backupExportState.activeKind = null
                                if (navigator.current == AppRoute.DataBackup) showDataBackup()
                            }
                    }
                }
            navRow(R.drawable.wna_download, "Import backup", "Merge a JSON backup into this library", chevron = false) {
                importBackupLauncher.launch(arrayOf("application/json", "text/*"))
            }
            loadingRow(
                R.drawable.wna_archive,
                "Create full backup",
                "Everything, including chapters",
                backupExportState.activeKind == BackupExportKind.FULL,
            ) {
                startFullBackupExport()
            }
            navRow(R.drawable.wna_restore, "Restore full backup", "Replace this library with a full ZIP backup", chevron = false) {
                importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            navRow(
                R.drawable.wna_history,
                "Recover chapters from backup",
                "Add back downloaded chapters a full backup has; nothing is replaced",
                chevron = false,
            ) {
                recoverChaptersLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        addFullBackupProgressCard(this)
        addBackupFilesSection(this)

        section("Sources & storage")
        settingsGroup { addSourceRows(this) }

        section("Danger zone").setTextColor(colors.error)
        tintedCard(colors.error) {
            iconColumnRow(R.drawable.wna_delete, colors.error).apply {
                addView(makeText(context, "Clear local storage", Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP })
                addView(
                    makeText(
                        context,
                        "Deletes every novel, chapter and setting on this phone.",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply {
                        setPadding(0, dp(2), 0, 0)
                    },
                )
            }
            addView(
                makeButton(context, "Clear local storage", Btn.ERROR) {
                    confirm("Delete all novels, settings, and downloads? Make a backup first.", confirmLabel = "Delete everything") {
                        scope.launch {
                            repository.clearAll()
                            showLibrary()
                        }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.MD)
                },
            )
        }
    }
}

private fun ScreenHost.addSourceRows(group: LinearLayout) {
    val colors = ThemeManager.colors
    val storageHealth = repository.getStorageHealth()
    if (storageHealth.requiresUserAttention) {
        group.navRow(
            R.drawable.wna_error,
            "Storage recovery notice",
            "${storageHealth.issues.size} storage issue(s); preserved data was not overwritten",
            tint = colors.error,
            chevron = false,
        ) {
            toast(storageHealth.issues.joinToString("\n") { issue -> "${issue.document}: ${issue.detail}" })
        }
    }
    val sourceNetwork = app.appContainer.network
    val browserSessionSources = SourceRegistry.all().filter { it.descriptor.managesBrowserSession }
    val sourceSnapshots =
        sourceNetwork
            .reliabilitySnapshots()
            .filter { snapshot -> SourceRegistry.providerForHost(snapshot.host)?.descriptor?.managesBrowserSession == true }
    val webViewVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "unavailable"
    val (accessStatus, accessTint) =
        when {
            sourceSnapshots.any { it.manualVerificationRequired } -> "Verification required" to colors.primary
            sourceSnapshots.any { it.browserTransportActive } -> "Chromium transport active" to colors.primary
            sourceSnapshots.any { it.cooldownRemainingMillis > 0L } -> "Cooling down" to colors.secondary
            else -> "Ready" to colors.tertiary
        }
    group.navRow(
        R.drawable.wna_verified_user,
        "Source access status",
        tinted("$accessStatus · WebView $webViewVersion", accessTint),
        chevron = false,
    ) {
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
    group.navRow(R.drawable.wna_article, "Share activity logs", "The last 7 days of syncs, downloads and errors") {
        exportAndShare({
            ActivityLogExporter.export(
                directory = app.appContainer.repository.storage.backupRoot,
                library = repository.library(),
                queue = repository.queue(),
                reliability = sourceNetwork.reliabilitySnapshots(),
            )
        })
    }
    group.navRow(R.drawable.wna_bug_report, "Share source access logs", "No URLs, titles or cookies") {
        exportAndShare({ BypassLogExporter.export(app.appContainer.repository.storage.backupRoot, sourceNetwork, repository.queue()) })
    }
    group.navRow(R.drawable.wna_favorite, "Patreon account", patreonAccountSummary(), tint = colors.secondary) { showPatreonAccount() }
    group.navRow(R.drawable.wna_restore, "Reset source web session", "Clears source cookies, browser storage and cooldowns") {
        confirm("Reset source browser sessions? The next request may need a quick check again.", confirmLabel = "Reset") {
            resetSourceWebSessions(browserSessionSources)
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
