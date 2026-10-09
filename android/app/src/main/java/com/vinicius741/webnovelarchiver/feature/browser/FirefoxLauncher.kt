package com.vinicius741.webnovelarchiver.feature.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Hands a page to Firefox specifically, for flows that need a browser add-on (the Patreon sign-in
 * guide copies a cookie with Cookie-Editor). Firefox is visible to the package manager through the
 * manifest's browsable-https `<queries>` entry.
 */
internal object FirefoxLauncher {
    private const val RELEASE_PACKAGE = "org.mozilla.firefox"

    /** Release first; Beta and Nightly (`fenix`) run the same add-ons. */
    private val packages = listOf(RELEASE_PACKAGE, "org.mozilla.firefox_beta", "org.mozilla.fenix")

    fun installedPackage(context: Context): String? =
        packages.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /** Opens [url] in the installed Firefox; false when Firefox is missing or refuses the intent. */
    fun open(
        context: Context,
        url: String,
    ): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openStoreListing(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$RELEASE_PACKAGE")))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$RELEASE_PACKAGE")))
        }
    }
}
