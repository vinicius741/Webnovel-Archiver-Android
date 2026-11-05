package com.vinicius741.webnovelarchiver.appupdate

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

sealed interface AppUpdateState {
    /** The release this state refers to, when there is one. */
    val update: AvailableUpdate? get() = null

    /** True while a check, download, or install is running; a new check must wait. */
    val isBusy: Boolean get() = false

    data object Idle : AppUpdateState

    data object Checking : AppUpdateState {
        override val isBusy: Boolean get() = true
    }

    data class UpToDate(
        val latestTag: String,
    ) : AppUpdateState

    data class Available(
        override val update: AvailableUpdate,
    ) : AppUpdateState

    data class Downloading(
        override val update: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateState {
        override val isBusy: Boolean get() = true
    }

    data class ReadyToInstall(
        override val update: AvailableUpdate,
        val apk: java.io.File,
    ) : AppUpdateState

    data class Installing(
        override val update: AvailableUpdate,
    ) : AppUpdateState {
        override val isBusy: Boolean get() = true
    }

    /** Android needs the user to confirm; [confirmIntent] opens its install dialog. */
    data class AwaitingConfirmation(
        override val update: AvailableUpdate,
        val confirmIntent: Intent,
    ) : AppUpdateState {
        override val isBusy: Boolean get() = true
    }

    data class Failed(
        val message: String,
        override val update: AvailableUpdate?,
    ) : AppUpdateState
}

/**
 * Device-local update bookkeeping in SharedPreferences: not library data, so it is neither in
 * [com.vinicius741.webnovelarchiver.data.repository.AppRepository] nor in backups.
 */
class AppUpdatePreferences(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    var lastCheckedAtMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECKED_AT, value) }

    /** The last fetched release, trimmed by [GitHubReleaseParsing.serializeRelease]. */
    var cachedRelease: String?
        get() = prefs.getString(KEY_CACHED_RELEASE, null)
        set(value) = prefs.edit { putString(KEY_CACHED_RELEASE, value) }

    /** The release the startup dialog last offered; it is offered once. */
    var promptedTag: String?
        get() = prefs.getString(KEY_PROMPTED_TAG, null)
        set(value) = prefs.edit { putString(KEY_PROMPTED_TAG, value) }

    /** A release the user chose to skip; the startup dialog never offers it. */
    var skippedTag: String?
        get() = prefs.getString(KEY_SKIPPED_TAG, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED_TAG, value) }

    private companion object {
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_CACHED_RELEASE = "cached_release"
        const val KEY_PROMPTED_TAG = "prompted_tag"
        const val KEY_SKIPPED_TAG = "skipped_tag"
    }
}
