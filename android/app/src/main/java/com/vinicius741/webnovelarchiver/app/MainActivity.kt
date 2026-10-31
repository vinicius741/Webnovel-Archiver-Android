package com.vinicius741.webnovelarchiver.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.app.renderRouteDispatch
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.ai.AiControlsScreenState
import com.vinicius741.webnovelarchiver.feature.ai.attachAiJobChip
import com.vinicius741.webnovelarchiver.feature.browser.ACTION_IMPORT_CURRENT_URL
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.importFromBrowser
import com.vinicius741.webnovelarchiver.feature.cleanup.CleanupScreenState
import com.vinicius741.webnovelarchiver.feature.details.detachDetailsTtsListener
import com.vinicius741.webnovelarchiver.feature.library.LibraryScreenStatePersistence
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.player.attachTtsMiniPlayer
import com.vinicius741.webnovelarchiver.feature.reader.detachReaderTtsListener
import com.vinicius741.webnovelarchiver.feature.settings.showDataBackup
import com.vinicius741.webnovelarchiver.feature.settings.showNotifications
import com.vinicius741.webnovelarchiver.navigation.AddStoryScreenState
import com.vinicius741.webnovelarchiver.navigation.AppNavigator
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportState
import com.vinicius741.webnovelarchiver.navigation.LibraryScreenState
import com.vinicius741.webnovelarchiver.navigation.RouteViewMemory
import com.vinicius741.webnovelarchiver.navigation.ScreenBack
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.navigation.runUiOperation
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation
import com.vinicius741.webnovelarchiver.perf.PerfRecorder
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.captureScreenViewState
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vinicius741.webnovelarchiver.app.notificationPermissionActionLabel as notificationPermissionActionLabelExt
import com.vinicius741.webnovelarchiver.app.performNotificationPermissionAction as performNotificationPermissionActionExt
import com.vinicius741.webnovelarchiver.app.requestNotificationPermissionForDownload as requestNotificationPermissionForDownloadExt

/**
 * App entry point: lifecycle and wiring only — engines, the root [frame], backup launchers.
 * Screen rendering, navigation, and actions live in the ScreenHost extension files.
 */
class MainActivity :
    AppCompatActivity(),
    ScreenHost {
    override val app: AppCompatActivity get() = this

    /** UI scope wrapping [lifecycleScope] so screen-launched coroutines are cancelled on destroy. */
    override val scope: CoroutineScope by lazy { CoroutineScope(lifecycleScope.coroutineContext) }
    override lateinit var repository: AppRepository
    override lateinit var syncEngine: StorySyncEngine
    override lateinit var downloadEngine: DownloadEngine
    override lateinit var epubEngine: EpubEngine
    override lateinit var ttsEngine: TtsEngine
    override lateinit var frame: FrameLayout
    override var activeStory: Story? = null
    override var storyOperation: StoryOperationState? = null
    override var detailsOperationSlot: ViewGroup? = null
    override val navigator = AppNavigator()
    override val routeMemory: RouteViewMemory = RouteViewMemory()
    override val addStoryScreenState: AddStoryScreenState = AddStoryScreenState()
    override val updateTrackerScreenState: UpdateTrackerScreenState = UpdateTrackerScreenState()
    override val backupExportState: BackupExportState = BackupExportState()
    override val aiControlsScreenState: AiControlsScreenState = AiControlsScreenState()
    override val libraryScreenState: LibraryScreenState = LibraryScreenState()
    override val cleanupScreenState: CleanupScreenState = CleanupScreenState()

    // Lazy: seeded from cached DisplayPreferences after repository startup hydration.
    override val updateFollowSelectionState: UpdateFollowSelectionState by lazy {
        UpdateFollowSelectionState().apply {
            showCovers = repository.getDisplayPreferences().showCoversOnUpdates
        }
    }
    override val storyExpandOverride: MutableMap<String, Boolean> = mutableMapOf()

    /** Re-render the screen currently on [frame]; set by each screen so config changes can reflow it. */
    override var rerender: (() -> Unit)? = null
    override var screenObserver: Job? = null
    override var onScreenBuilt: (() -> Unit)? = null

    /** Created in [onCreate] once engines/storage are up. */
    override lateinit var foldTracker: FoldTracker
    private var uiReady = false
    private var restoredNavigation = false

    /**
     * True once [onNewIntent] replaced the launch intent before the UI was ready. A notification tap
     * into a process-killed task recreates the activity with its saved stack and the original
     * launch intent, then hands the tap over as a pending new intent, so it lands here, not as
     * the launch intent.
     */
    private var intentArrivedBeforeUiReady = false

    /**
     * Always registered, enabled only while the screen handles Back (see [refreshBackCallback]);
     * disabled otherwise so the OS predictive back-to-home applies.
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (screenBack?.handle() == true) {
                    refreshBackCallback()
                    return
                }
                // The screen's state changed since the callback was enabled: let Android leave the app.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                refreshBackCallback()
            }
        }

    override val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (uiReady && navigator.current == AppRoute.Notifications) showNotifications()
        }

    override var screenBack: ScreenBack? = null
        set(value) {
            field = value
            refreshBackCallback()
        }

    override fun refreshBackCallback() {
        backCallback.isEnabled = screenBack?.handlesBack == true
    }

    override val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            // A failed import must return the user to the backup screen with a visible error,
            // not die in the coroutine handler.
            runUiOperation(
                "import json backup",
                onExpectedError = { error ->
                    toast(error.message ?: "Import failed")
                    showDataBackup()
                },
            ) {
                toast(repository.importBackupUri(uri))
                showDataBackup()
            }
        }

    override val importFullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runUiOperation(
                "import full backup",
                onExpectedError = { error ->
                    toast(error.message ?: "Restore failed")
                    showDataBackup()
                },
            ) {
                val outcome = repository.importFullBackupUri(uri)
                toast(outcome)
                // R12: restore failures return a message string, not an exception. Most carry
                // RESTORE_FAILED_PREFIX; verify-stage ("Restore verify failed: …") and missing-input
                // returns don't, so only restoreSummary's "Restored …" counts as success.
                if (outcome.startsWith(FullBackupRestorePlanning.RESTORE_FAILED_PREFIX) || !outcome.startsWith("Restored")) {
                    showDataBackup()
                } else {
                    showLibrary()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredNavigation = restoreNavigationState(savedInstanceState)
        startPerfSessionIfRequested()
        // Screens render into `frame`; the TTS mini-player floats above them in `root` so it
        // survives every screen rebuild.
        frame = FrameLayout(this)
        val root = FrameLayout(this)
        root.addView(
            frame,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        holdSplashScreenUntilFirstContent { uiReady }
        onBackPressedDispatcher.addCallback(this, backCallback)
        scope.launch {
            val startup =
                runCatching {
                    // Read the first-frame theme hint off the UI thread while container setup runs.
                    withContext(Dispatchers.IO) { StartupThemeHint.read(this@MainActivity) }
                        ?.let(ThemeManager::apply)
                    showStartupLoading()
                    val container = (application as WebnovelArchiverApp).awaitContainer()
                    repository = container.repository
                    syncEngine = container.syncEngine
                    epubEngine = container.epubEngine
                    // Control/enqueue handle only; the service owns the process loop.
                    downloadEngine =
                        DownloadEngine(
                            repository,
                            container.network,
                            container.downloadPacer,
                            ownsProcessLoop = false,
                        )
                    ttsEngine = container.ttsEngine
                    attachTtsMiniPlayer(root)
                    attachAiJobChip(root)
                    attachAiJobBridges()
                    container.awaitRepositoryReady()
                    initializeUiAfterRepositoryReady()
                }
            startup.onFailure(::showStartupFailure)
        }
    }

    /** Initializes storage-backed UI state only after migration and repository hydration finish. */
    private suspend fun initializeUiAfterRepositoryReady() {
        // Before startup-state resolution so dev targets and TTS resume see the restored library.
        val devRestoreAttempted =
            if (BuildConfig.DEBUG) {
                maybeRestoreFullBackupForDev()
            } else {
                false
            }
        val startupState =
            run {
                val resumeTarget =
                    TtsSessionPlanning.readerResumeTarget(repository.getTtsSession()) { storyId ->
                        repository.story(storyId)
                    }
                val devTarget =
                    if (BuildConfig.DEBUG) {
                        DevLaunchPlanning.resolve(
                            screenName = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_SCREEN),
                            storyOverride = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_STORY),
                            chapterOverride = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_CHAPTER),
                            libraryProvider = repository::library,
                        )
                    } else {
                        null
                    }
                InitialStartupState(
                    activeThemeId = repository.getDisplayPreferences().activeThemeId,
                    resumeTarget = resumeTarget,
                    devTarget = devTarget,
                )
            }
        ThemeManager.apply(startupState.activeThemeId)
        withContext(Dispatchers.IO) { StartupThemeHint.write(this@MainActivity, startupState.activeThemeId) }
        applyWindowTheme()
        if (BuildConfig.DEBUG &&
            (
                devRestoreAttempted ||
                    DevLibraryReportPlanning.requested(intent.getStringExtra(DevLibraryReportPlanning.EXTRA_DEV_LIBRARY_REPORT))
            )
        ) {
            writeDevLibraryReport()
        }
        // All configChanges are declared in the manifest, so fold/unfold/rotation never recreates
        // the activity — observe the fold sensor and re-render for the layout to adapt.
        foldTracker = FoldTracker(this, scope)
        scope.launch {
            foldTracker.isFoldingFeature.collect { runOnUiThread { rerender?.invoke() } }
        }
        uiReady = true
        recordUiReadyForPerf()
        routeInitialIntent(intent, startupState)
    }

    private fun routeInitialIntent(
        intent: Intent,
        startupState: InitialStartupState,
    ) {
        val browserImportUrl = browserImportUrl(intent)
        val resumeTarget = startupState.resumeTarget
        val devTarget = startupState.devTarget
        // A restored stack already holds whatever the launch intent's notification tap opened; only
        // a tap delivered since then is new.
        val notificationRoute = intent.openRoute()?.takeUnless { restoredNavigation && !intentArrivedBeforeUiReady }
        when {
            devTarget != null -> openDeepRoute(devTarget)
            browserImportUrl != null -> {
                showLibrary()
                importFromBrowser(browserImportUrl)
            }
            // Same as a live tap: on top of the restored screen, so Back returns there.
            notificationRoute != null && restoredNavigation -> renderRoute(notificationRoute)
            notificationRoute != null -> openDeepRoute(notificationRoute)
            restoredNavigation -> renderRoute(navigator.current)
            resumeTarget != null -> openDeepRoute(AppRoute.Reader(resumeTarget.storyId, resumeTarget.chapterId))
            else -> showLibrary()
        }
    }

    /** Opens a cold-start target on top of its parents, so Back walks up to the Library instead of out. */
    private fun openDeepRoute(route: AppRoute) {
        navigator.resetTo(route)
        renderRoute(route)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!uiReady) {
            // routeInitialIntent picks this intent up once startup finishes.
            intentArrivedBeforeUiReady = true
            return
        }
        browserImportUrl(intent)?.let { url ->
            showLibrary()
            importFromBrowser(url)
            return
        }
        // A notification tap opens its screen on top of the current one, so Back returns there.
        intent.openRoute()?.let(::renderRoute)
    }

    override fun onResume() {
        super.onResume()
        SourceAccessRetryCoordinator.consumeReadyRetry()?.invoke()
        if (uiReady && navigator.current == AppRoute.Notifications) showNotifications()
        // Returning from Firefox: refresh which setup steps are done.
        if (uiReady && navigator.current is AppRoute.PatreonAccount) rerender?.invoke()
    }

    override fun onUserInteraction() {
        recordInteractionForPerf()
        super.onUserInteraction()
    }

    override fun onDestroy() {
        finishPerfSession()
        aiControlsScreenState.binding = null
        screenObserver?.cancel()
        // Destroy lingering reader WebViews so they can't leak the activity reference.
        com.vinicius741.webnovelarchiver.platform.WebViewSafety
            .disposeAll(frame)
        // Detach the reader/details TTS observers so they can't fire into a destroyed activity
        // (the engine is process-wide; only the listener is dropped).
        detachReaderTtsListener()
        detachDetailsTtsListener()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureScreenViewState()
        outState.putStringArrayList(STATE_ROUTE_STACK, ArrayList(navigator.encodedStack()))
        routeMemory.save(outState)
        LibraryScreenStatePersistence.save(libraryScreenState, outState)
        cleanupScreenState.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun renderRoute(route: AppRoute) = renderRouteDispatch(route)

    private fun restoreNavigationState(state: Bundle?): Boolean {
        state ?: return false
        // Library view memory restores even when the route stack can't: the root Library screen uses it too.
        LibraryScreenStatePersistence.restore(libraryScreenState, state)
        cleanupScreenState.restore(state)
        val stack = state.getStringArrayList(STATE_ROUTE_STACK) ?: return false
        if (!navigator.restore(stack)) return false
        if (navigator.current == AppRoute.Working) {
            navigator.reset()
            return false
        }
        routeMemory.restore(state)
        return true
    }

    /**
     * The manifest declares all configChanges, so Android never recreates this activity on
     * rotate/fold/theme change — re-render so the responsive layout reflows immediately.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rerender?.invoke()
    }

    private fun applyWindowTheme() {
        val t = ThemeManager.current
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = t.colors.background
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !t.isDark
    }

    override fun notificationPermissionActionLabel(): String = notificationPermissionActionLabelExt()

    override fun performNotificationPermissionAction() {
        performNotificationPermissionActionExt()
    }

    override fun requestNotificationPermissionForDownload() {
        requestNotificationPermissionForDownloadExt()
    }

    private fun browserImportUrl(intent: Intent?): String? =
        intent
            ?.dataString
            ?.takeIf { intent.action == ACTION_IMPORT_CURRENT_URL }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private data class InitialStartupState(
        val activeThemeId: String,
        val resumeTarget: TtsSessionPlanning.ReaderResumeTarget?,
        val devTarget: AppRoute?,
    )

    private companion object {
        const val STATE_ROUTE_STACK = "navigation.route_stack"
    }
}

internal fun MainActivity.startPerfSessionIfRequested() {
    if (BuildConfig.DEBUG) PerfRecorder.maybeStartFromIntent(this, intent)
}

internal fun MainActivity.recordUiReadyForPerf() {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordUiReady()
}

internal fun MainActivity.recordInteractionForPerf() {
    if (BuildConfig.DEBUG) PerfRecorder.recordInteraction()
}

internal fun MainActivity.finishPerfSession() {
    if (BuildConfig.DEBUG) PerfRecorder.onActivityDestroyed()
}
