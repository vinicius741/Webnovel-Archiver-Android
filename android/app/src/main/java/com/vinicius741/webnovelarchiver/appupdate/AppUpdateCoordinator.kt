package com.vinicius741.webnovelarchiver.appupdate

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Process-wide owner of app-update checks, the APK download, and the install hand-off, so a
 * download keeps going while the user navigates.
 *
 * [installSupported] is false outside the release build: a debug build can check, but a release
 * APK is a different application id and would install beside it instead of updating it. A null
 * [client] (the offline simulation) disables every request.
 */
class AppUpdateCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val client: GitHubReleaseClient?,
    val installedVersionName: String,
    val installSupported: Boolean,
    private val nowMillis: () -> Long,
) {
    private val appContext = context.applicationContext
    val preferences = AppUpdatePreferences(appContext)
    private val updateDir = File(appContext.cacheDir, "app_update")
    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state

    val checksEnabled: Boolean get() = client != null

    val installedVersionCode: Long by lazy { AppUpdateInstaller.installedVersionCode(appContext) }

    /** Restores the last check's answer so Settings shows a found update without a new request. */
    fun restoreCachedRelease() {
        val release = preferences.cachedRelease?.let(GitHubReleaseParsing::parseRelease) ?: return
        if (_state.value == AppUpdateState.Idle) applyEvaluation(release)
    }

    fun checkNow(): Job = scope.launch { check() }

    /**
     * Checks when automatic checks are on and the interval has passed, then returns an update the
     * startup dialog should offer (at most once per release, never a skipped one).
     */
    suspend fun autoCheckForPrompt(): AvailableUpdate? {
        // Debug builds never prompt: their application id cannot take a release update.
        if (!checksEnabled || !installSupported) return null
        val due = AppUpdatePlanning.shouldAutoCheck(preferences.autoCheckEnabled, nowMillis(), preferences.lastCheckedAtMillis)
        // Runs on the process scope so leaving the activity cannot strand the check mid-request.
        if (due) checkNow().join()
        val update = (_state.value as? AppUpdateState.Available)?.update ?: return null
        return update.takeIf { AppUpdatePlanning.shouldPrompt(it.tag, preferences.promptedTag, preferences.skippedTag) }
    }

    private suspend fun check() {
        val github = client ?: return
        if (!checkLock.tryLock()) return
        try {
            val previous = _state.value
            if (previous.isBusy) return
            _state.value = AppUpdateState.Checking
            val release =
                try {
                    github.fetchLatestRelease()
                } catch (cancel: CancellationException) {
                    _state.value = previous
                    throw cancel
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    // OkHttp reports offline and TLS problems as several exception types.
                    Timber.w(error, "App update check failed")
                    _state.value = AppUpdateState.Failed(error.message ?: "Could not reach GitHub", previous.update)
                    return
                }
            preferences.lastCheckedAtMillis = nowMillis()
            preferences.cachedRelease = GitHubReleaseParsing.serializeRelease(release)
            applyEvaluation(release)
        } finally {
            checkLock.unlock()
        }
    }

    private fun applyEvaluation(release: LatestRelease) {
        _state.value =
            when (val evaluation = AppUpdatePlanning.evaluate(release, installedVersionCode, installedVersionName)) {
                is UpdateEvaluation.UpToDate -> {
                    updateDir.deleteRecursively()
                    AppUpdateState.UpToDate(evaluation.latestTag)
                }
                is UpdateEvaluation.Available -> AppUpdateState.Available(evaluation.update)
                is UpdateEvaluation.Unusable -> AppUpdateState.Failed(evaluation.reason, null)
            }
    }

    /** Downloads and verifies the APK, reusing an already verified copy of the same release. */
    fun download(update: AvailableUpdate) {
        val github = client ?: return
        if (!installSupported || downloadJob?.isActive == true) return
        downloadJob =
            scope.launch {
                _state.value = AppUpdateState.Downloading(update, 0L, update.apk.size)
                try {
                    val expected = github.expectedChecksum(update)
                    val target = apkFile(updateDir, update)
                    if (!(target.isFile && GitHubReleaseClient.sha256(target) == expected)) {
                        updateDir.deleteRecursively()
                        updateDir.mkdirs()
                        var lastPercent = -1
                        val actual =
                            github.download(this, update.apk.downloadUrl, target) { downloaded, total ->
                                val size = if (total > 0) total else update.apk.size
                                val percent = if (size > 0) (downloaded * 100 / size).toInt() else -1
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.value = AppUpdateState.Downloading(update, downloaded, size)
                                }
                            }
                        if (actual != expected) {
                            target.delete()
                            throw IOException("The download did not match the release checksum")
                        }
                    }
                    _state.value = AppUpdateState.ReadyToInstall(update, target)
                } catch (cancel: CancellationException) {
                    _state.value = AppUpdateState.Available(update)
                    throw cancel
                } catch (error: IOException) {
                    Timber.w(error, "App update download failed")
                    _state.value = AppUpdateState.Failed(error.message ?: "Download failed", update)
                }
            }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Verifies the downloaded APK and opens an installer session. */
    fun install(
        update: AvailableUpdate,
        apk: File,
    ) {
        if (!installSupported || _state.value.isBusy) return
        if (!apk.isFile) {
            _state.value = AppUpdateState.Available(update)
            return
        }
        _state.value = AppUpdateState.Installing(update)
        scope.launch {
            val rejection =
                runCatching { AppUpdateInstaller.rejection(appContext, apk) }
                    .getOrElse { it.message ?: "Could not read the downloaded update" }
            if (rejection != null) {
                apk.delete()
                _state.value = AppUpdateState.Failed(rejection, update)
                return@launch
            }
            runCatching { AppUpdateInstaller.install(appContext, apk) }
                .onFailure { error ->
                    Timber.w(error, "App update install session failed")
                    _state.value = AppUpdateState.Failed(error.message ?: "Could not start the installer", update)
                }
        }
    }

    internal fun onConfirmationRequired(confirmIntent: Intent) {
        val update = _state.value.update ?: return
        _state.value = AppUpdateState.AwaitingConfirmation(update, confirmIntent)
    }

    /** Installer result. Success is rarely seen here: Android replaces this process when the update lands. */
    internal fun onInstallFinished(
        success: Boolean,
        message: String?,
        canceled: Boolean = false,
    ) {
        val update = _state.value.update
        val apk = update?.let { apkFile(updateDir, it) }
        _state.value =
            when {
                success -> {
                    updateDir.deleteRecursively()
                    AppUpdateState.UpToDate(update?.tag.orEmpty())
                }
                canceled && update != null && apk?.isFile == true -> AppUpdateState.ReadyToInstall(update, apk)
                else -> AppUpdateState.Failed(message ?: "Update failed", update)
            }
    }
}

/** The verified download's path; the tag is reduced to file-name-safe characters. */
private fun apkFile(
    dir: File,
    update: AvailableUpdate,
): File = File(dir, "${update.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
