package com.vinicius741.webnovelarchiver.feature.browser

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.drawable.toBitmap
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.feature.story.isNovelUrl
import com.vinicius741.webnovelarchiver.feature.story.resolveUrl
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

/**
 * Opens third-party novel sites in a browser-powered Custom Tab. Unlike an embedded WebView, this
 * is an approved user agent for Google OAuth and shares the user's browser cookies, accounts, and
 * password manager. The app still receives the current page URL through the Import action.
 */
internal fun ScreenHost.showBrowser(startUrl: String) {
    val url = resolveUrl(startUrl)
    if (url.isBlank()) return toast("Enter a URL")

    val importIntent =
        Intent(app, MainActivity::class.java).apply {
            action = BrowserImportPlanning.ACTION_IMPORT_CURRENT_URL
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val importPendingIntent =
        PendingIntent.getActivity(
            app,
            BrowserImportPlanning.IMPORT_REQUEST_CODE,
            importIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )

    val iconSize = app.dp(24)
    val colors = ThemeManager.colors
    val importIcon =
        requireNotNull(app.tintedIcon(R.drawable.wna_download, colors.onSurface))
            .toBitmap(iconSize, iconSize)
    val closeIcon =
        requireNotNull(app.tintedIcon(R.drawable.wna_close, colors.onSurface))
            .toBitmap(iconSize, iconSize)
    val customTab =
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams
                    .Builder()
                    .setToolbarColor(colors.elevation2)
                    .setNavigationBarColor(colors.background)
                    .build(),
            )
            .setCloseButtonIcon(closeIcon)
            .setActionButton(importIcon, "Import novel", importPendingIntent, true)
            .addMenuItem("Import novel", importPendingIntent)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()

    runCatching { customTab.launchUrl(app, Uri.parse(url)) }
        .onFailure {
            runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast("No browser is available") }
        }
}

/** Validates and imports the URL returned by the Custom Tab action. */
internal fun ScreenHost.importFromBrowser(url: String) {
    if (!isNovelUrl(url)) {
        toast("Open a supported novel page before importing")
        return
    }

    val tabs = storage.getTabs().sortedBy { it.order }
    if (tabs.isEmpty()) {
        syncStory(url, null)
    } else {
        showStyledOptionsDialog(
            "Import to library",
            tabs.map { tab -> tab.name to { syncStory(url, tab.id) } },
        )
    }
}
