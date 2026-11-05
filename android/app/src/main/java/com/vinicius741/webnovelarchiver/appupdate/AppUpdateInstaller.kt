package com.vinicius741.webnovelarchiver.appupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.vinicius741.webnovelarchiver.app.appContainer
import timber.log.Timber
import java.io.File

/**
 * Hands a verified APK to Android's [PackageInstaller]. Android still checks that the update is
 * signed with the installed app's key; [rejection] checks the same first so a mismatch reads as a
 * clear message instead of a generic installer failure.
 */
internal object AppUpdateInstaller {
    private const val ACTION_INSTALL_STATUS = "com.vinicius741.webnovelarchiver.appupdate.INSTALL_STATUS"

    fun canRequestInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun installedVersionCode(context: Context): Long = PackageInfoCompat.getLongVersionCode(installedInfo(context))

    fun rejection(
        context: Context,
        apk: File,
    ): String? {
        val pm = context.packageManager
        val archive =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
                } else {
                    pm.getPackageArchiveInfo(apk.path, signatureFlags())
                }
            }.getOrNull()
        val installed = installedInfo(context)
        val archiveSigners = archive?.signerDigests().orEmpty()
        return AppUpdatePlanning.archiveRejection(
            archivePackage = archive?.packageName,
            archiveVersionCode = archive?.let(PackageInfoCompat::getLongVersionCode),
            installedPackage = context.packageName,
            installedVersionCode = PackageInfoCompat.getLongVersionCode(installed),
            signaturesMatch = archiveSigners.isNotEmpty() && archiveSigners == installed.signerDigests(),
        )
    }

    /** Writes [apk] into an installer session and commits it; the result arrives at [AppUpdateInstallReceiver]. */
    fun install(
        context: Context,
        apk: File,
    ) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                // Skips the confirmation only once Android records this app as its own installer.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val sessionId = installer.createSession(params)
        runCatching { writeAndCommit(context, installer.openSession(sessionId), sessionId, apk) }
            .onFailure { runCatching { installer.abandonSession(sessionId) } }
            .getOrThrow()
    }

    private fun writeAndCommit(
        context: Context,
        session: PackageInstaller.Session,
        sessionId: Int,
        apk: File,
    ) = session.use {
        session.openWrite("base.apk", 0, apk.length()).use { output ->
            apk.inputStream().use { input -> input.copyTo(output) }
            session.fsync(output)
        }
        val statusIntent =
            Intent(context, AppUpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
        // Mutable: the installer adds its status extras to this explicit intent.
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        session.commit(PendingIntent.getBroadcast(context, sessionId, statusIntent, flags).intentSender)
    }

    private fun installedInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            context.packageManager.getPackageInfo(context.packageName, signatureFlags())
        }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** The current signer set. Key rotation history is ignored: releases keep one original key. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = signingInfo ?: return emptySet()
                if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                        ?.takeLast(
                            1,
                        )?.toTypedArray()
                }
            } else {
                signatures
            }
        return signatures
            .orEmpty()
            .map { GitHubReleaseClient.sha256Bytes(it.toByteArray()) }
            .toSet()
    }
}

/** Receives [PackageInstaller] session status and forwards it to the process-wide coordinator. */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val coordinator = context.appContainer.appUpdates
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                if (confirm == null) {
                    coordinator.onInstallFinished(success = false, message = "Android did not ask to confirm the update")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                coordinator.onConfirmationRequired(confirm)
                // The app is normally in front (the user just tapped Install); otherwise the
                // App Updates screen offers the same confirmation once it is visible again.
                runCatching { context.startActivity(confirm) }
                    .onFailure { Timber.w(it, "Could not open the update confirmation") }
            }
            PackageInstaller.STATUS_SUCCESS -> coordinator.onInstallFinished(success = true, message = null)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                coordinator.onInstallFinished(
                    success = false,
                    message = "Update canceled",
                    canceled = true,
                )
            else ->
                coordinator.onInstallFinished(
                    success = false,
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update",
                )
        }
    }
}
