package com.vinicius741.webnovelarchiver.appupdate

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.File

/**
 * Process-wide owner of app-update checks. The app only finds a newer release; the APK is
 * downloaded and installed by the browser and Android's own installer, so this app holds no
 * install-packages permission (that permission plus self-installing APKs is what Play Protect
 * flags in sideloaded apps).
 *
 * [releaseBuild] is false outside the release build: a debug build can check, but a release APK
 * is a different application id and would install beside it instead of updating it. A null
 * [client] (the offline simulation) disables every request.
 */
class AppUpdateCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val client: GitHubReleaseClient?,
    val installedVersionName: String,
    val releaseBuild: Boolean,
    private val nowMillis: () -> Long,
) {
    private val appContext = context.applicationContext
    val preferences = AppUpdatePreferences(appContext)
    private val checkLock = Mutex()
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state

    init {
        // Earlier builds downloaded update APKs here; drop any copy they left behind.
        scope.launch { File(appContext.cacheDir, "app_update").deleteRecursively() }
    }

    val checksEnabled: Boolean get() = client != null

    val installedVersionCode: Long by lazy {
        PackageInfoCompat.getLongVersionCode(appContext.packageManager.getPackageInfo(appContext.packageName, 0))
    }

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
        if (!checksEnabled || !releaseBuild) return null
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
                is UpdateEvaluation.UpToDate -> AppUpdateState.UpToDate(evaluation.latestTag)
                is UpdateEvaluation.Available -> AppUpdateState.Available(evaluation.update)
                is UpdateEvaluation.Unusable -> AppUpdateState.Failed(evaluation.reason, null)
            }
    }
}
