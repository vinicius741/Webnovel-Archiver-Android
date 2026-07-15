package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.browser.BrowserImportPlanning
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.importFromBrowser
import com.vinicius741.webnovelarchiver.feature.cleanup.showCleanupRules
import com.vinicius741.webnovelarchiver.feature.details.showChapterSelection
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.details.showLegacyEpubs
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.library.showAddStory
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.library.showLibrarySelection
import com.vinicius741.webnovelarchiver.feature.reader.detachReaderTtsListener
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.feature.settings.showNotifications
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.settings.showTabs
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.feature.updates.showUpdateFollowSelection
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.AddStoryScreenState
import com.vinicius741.webnovelarchiver.navigation.AppNavigator
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportState
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.notification.NotificationPermissionAction
import com.vinicius741.webnovelarchiver.notification.NotificationSettingsPlanning
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * App entry point. Owns lifecycle and wiring only: instantiates the storage/engines, the root
 * [frame], and the backup launchers, then hands the first screen off to the `ScreenHost`
 * extensions split across the `screens/`, `actions/`, and `ui/` files. All screen rendering,
 * navigation, and business actions live there — this class just implements [ScreenHost].
 */
class MainActivity :
    AppCompatActivity(),
    ScreenHost {
    override val app: AppCompatActivity get() = this

    /**
     * UI coroutine scope (Maintainability M3). A single [CoroutineScope] wrapping the activity's
     * [lifecycleScope] job/context, so all screen-launched coroutines (fold observation, backup
     * import, sync) are cancelled automatically when the activity is destroyed — no leaked work.
     */
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
    override val routeScrollPositions: MutableMap<String, Int> = mutableMapOf()
    override val addStoryScreenState: AddStoryScreenState = AddStoryScreenState()
    override val updateTrackerScreenState: UpdateTrackerScreenState = UpdateTrackerScreenState()
    override val backupExportState: BackupExportState = BackupExportState()

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

    /** Foldable detector. Created in [onCreate] once engines/storage are up. */
    override lateinit var foldTracker: FoldTracker
    private var uiReady = false
    private var restoredNavigation = false

    /**
     * The single system-back callback. Always registered, but enabled only while a screen has
     * provided in-app back navigation (see [backHandler]'s setter). Disabled on the root screen so
     * the OS default — exit to home, with the predictive-back home preview — applies unchanged.
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                backHandler?.invoke()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (uiReady && navigator.current == AppRoute.Notifications) showNotifications()
        }

    override var backHandler: (() -> Unit)? = null
        set(value) {
            field = value
            backCallback.isEnabled = value != null
        }

    override val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            scope.launch {
                toast(repository.importBackupUri(uri))
                showSettings()
            }
        }

    override val importFullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            scope.launch {
                toast(repository.importFullBackupUri(uri))
                showLibrary()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredNavigation = restoreNavigationState(savedInstanceState)
        // Pull process-wide dependencies from the AppContainer (M2): one AppStorage, one network
        // client, one set of engines shared with the foreground services (R3 single-owner).
        val container = appContainer
        repository = container.repository
        syncEngine = container.syncEngine
        epubEngine = container.epubEngine
        // The activity's download engine is a control/enqueue handle only (ownsProcessLoop = false):
        // it mutates the shared queue, but the foreground service owns the single process loop. The UI
        // pairs every resume/retry with `DownloadForegroundService.start(app)` so the service's loop
        // picks the work up. Two loops running at once would each honor their own concurrency cap and
        // double the effective parallelism, so only one engine may run the loop.
        downloadEngine = DownloadEngine(repository, container.network, ownsProcessLoop = false)
        // Shared process-wide TTS engine (M2): the same instance the TtsForegroundService plays
        // through, so the reader's multicast state listener fires for service-driven playback.
        ttsEngine = container.ttsEngine
        frame = FrameLayout(this)
        setContentView(frame)
        onBackPressedDispatcher.addCallback(this, backCallback)
        showStartupLoading()
        scope.launch {
            val startup =
                runCatching {
                    container.awaitRepositoryReady()
                    initializeUiAfterRepositoryReady()
                }
            startup.onFailure(::showStartupFailure)
        }
    }

    /** Initializes storage-backed UI state only after migration and repository hydration finish. */
    private suspend fun initializeUiAfterRepositoryReady() {
        val startupState =
            run {
                val resumeTarget =
                    TtsSessionPlanning.readerResumeTarget(repository.getTtsSession()) { storyId ->
                        repository.getStory(storyId)
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
        applyWindowTheme()
        // Foldable hinge/inner-display detection. The activity declares all configChanges in the
        // manifest, so fold/unfold/rotation does NOT recreate it — we must observe the fold sensor
        // (here) and re-render the live screen on change (below) for the responsive layout to adapt.
        foldTracker = FoldTracker(this, scope)
        scope.launch {
            foldTracker.isFoldingFeature.collect { runOnUiThread { rerender?.invoke() } }
        }
        uiReady = true
        routeInitialIntent(intent, startupState)
    }

    private fun routeInitialIntent(
        intent: Intent,
        startupState: InitialStartupState,
    ) {
        val browserImportUrl = browserImportUrl(intent)
        val resumeTarget = startupState.resumeTarget
        val devTarget = startupState.devTarget
        if (devTarget != null) {
            when (devTarget) {
                DevLaunchPlanning.DevStartTarget.Library -> showLibrary()
                DevLaunchPlanning.DevStartTarget.Queue -> showQueue()
                DevLaunchPlanning.DevStartTarget.Settings -> showSettings()
                DevLaunchPlanning.DevStartTarget.Notifications -> showNotifications()
                DevLaunchPlanning.DevStartTarget.Updates -> showUpdates()
                DevLaunchPlanning.DevStartTarget.AddStory -> showAddStory()
                is DevLaunchPlanning.DevStartTarget.Reader ->
                    showReader(devTarget.storyId, devTarget.chapterId)
                is DevLaunchPlanning.DevStartTarget.Details -> showDetails(devTarget.storyId)
            }
        } else if (browserImportUrl != null) {
            showLibrary()
            importFromBrowser(browserImportUrl)
        } else if (restoredNavigation) {
            renderRoute(navigator.current)
        } else if (resumeTarget != null) {
            showReader(resumeTarget.storyId, resumeTarget.chapterId)
        } else {
            showLibrary()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!uiReady) return
        browserImportUrl(intent)?.let { url ->
            showLibrary()
            importFromBrowser(url)
        }
    }

    override fun onResume() {
        super.onResume()
        SourceAccessRetryCoordinator.consumeReadyRetry()?.invoke()
        if (uiReady && navigator.current == AppRoute.Notifications) showNotifications()
    }

    override fun onDestroy() {
        screenObserver?.cancel()
        // R9: destroy any lingering reader WebView in the frame so it can't leak the activity
        // reference. Third-party browsing uses a browser-owned Custom Tab rather than this frame.
        com.vinicius741.webnovelarchiver.ui.WebViewSafety
            .disposeAll(frame)
        // Detach the reader's TTS observer (if a reader screen is active) so it can't fire into a
        // destroyed activity. The shared TTS engine is process-wide; only the listener is dropped.
        detachReaderTtsListener()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (frame.tag as? String)?.let { key ->
            com.vinicius741.webnovelarchiver.ui
                .findScrollView(frame)
                ?.let { routeScrollPositions[key] = it.scrollY }
        }
        outState.putStringArrayList(STATE_ROUTE_STACK, ArrayList(navigator.encodedStack()))
        val scrollEntries = routeScrollPositions.entries.sortedBy { it.key }
        outState.putStringArrayList(STATE_SCROLL_KEYS, ArrayList(scrollEntries.map { it.key }))
        outState.putIntArray(STATE_SCROLL_VALUES, scrollEntries.map { it.value }.toIntArray())
        super.onSaveInstanceState(outState)
    }

    override fun renderRoute(route: AppRoute) {
        when (route) {
            AppRoute.Library -> showLibrary()
            AppRoute.AddStory -> showAddStory()
            is AppRoute.LibrarySelection -> showLibrarySelection(route.selectedStoryIds)
            is AppRoute.Details -> showDetails(route.storyId)
            is AppRoute.ChapterSelection -> showChapterSelection(route.storyId, route.selectedChapterIds)
            is AppRoute.LegacyEpubs -> showLegacyEpubs(route.storyId)
            is AppRoute.Reader -> showReader(route.storyId, route.chapterId)
            AppRoute.Queue -> showQueue()
            AppRoute.Updates -> showUpdates()
            AppRoute.UpdateFollowSelection -> showUpdateFollowSelection()
            AppRoute.Settings -> showSettings()
            AppRoute.Notifications -> showNotifications()
            AppRoute.DownloadSettings -> showDownloadSettings()
            AppRoute.TtsSettings -> showTtsSettings()
            AppRoute.Tabs -> showTabs()
            AppRoute.CleanupRules -> showCleanupRules()
            AppRoute.Working -> showLibrary()
        }
    }

    private fun restoreNavigationState(state: Bundle?): Boolean {
        state ?: return false
        val stack = state.getStringArrayList(STATE_ROUTE_STACK) ?: return false
        if (!navigator.restore(stack)) return false
        if (navigator.current == AppRoute.Working) {
            navigator.reset()
            return false
        }
        val keys: List<String> = state.getStringArrayList(STATE_SCROLL_KEYS) ?: emptyList()
        val values = state.getIntArray(STATE_SCROLL_VALUES) ?: intArrayOf()
        keys.forEachIndexed { index, key -> values.getOrNull(index)?.let { routeScrollPositions[key] = it } }
        return true
    }

    /**
     * The manifest declares all configChanges (orientation, screenSize, screenLayout, uiMode,
     * smallestScreenSize), so Android does NOT recreate this activity on rotate/fold/unfold/theme
     * change. That preserves in-memory state, but the already-built view tree would otherwise never
     * adapt to the new size. Re-render whatever screen is on the [frame] so the responsive layout
     * (multi-column library, two-pane details, adaptive reader padding) reflows immediately.
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

    override fun notificationPermissionActionLabel(): String =
        when (notificationPermissionAction()) {
            NotificationPermissionAction.REQUEST_PERMISSION -> "Allow notifications"
            NotificationPermissionAction.OPEN_APP_SETTINGS -> "Open app notification settings"
        }

    override fun performNotificationPermissionAction() {
        when (notificationPermissionAction()) {
            NotificationPermissionAction.REQUEST_PERMISSION -> {
                markAutomaticNotificationPromptShown()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NotificationPermissionAction.OPEN_APP_SETTINGS ->
                startActivity(AppNotificationChannels.appSettingsIntent(this))
        }
    }

    override fun requestNotificationPermissionForDownload() {
        val shouldRequest =
            NotificationSettingsPlanning.shouldRequestAutomatically(
                runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                permissionGranted = AppNotificationChannels.hasPostNotificationsPermission(this),
                automaticPromptShown = automaticNotificationPromptShown(),
            )
        if (!shouldRequest) return
        // The automatic prompt is reachable from lifecycleScope-backed sync coroutines that
        // survive the app being backgrounded (UpdateSyncOrchestrator/in-place sync call queueDownload
        // after network IO). launch() on a STOPPED activity won't show a usable dialog yet would
        // still consume the one-time prompt, so defer it to the next foregrounded download instead.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isFinishing || isDestroyed) return
        markAutomaticNotificationPromptShown()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationPermissionAction(): NotificationPermissionAction =
        NotificationSettingsPlanning.settingsAction(
            runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            permissionGranted = AppNotificationChannels.hasPostNotificationsPermission(this),
            automaticPromptShown = automaticNotificationPromptShown(),
        )

    private fun automaticNotificationPromptShown(): Boolean =
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN, false)

    private fun markAutomaticNotificationPromptShown() {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN, true)
            .apply()
    }

    private fun browserImportUrl(intent: Intent?): String? = BrowserImportPlanning.importUrl(intent?.action, intent?.dataString)

    private data class InitialStartupState(
        val activeThemeId: String,
        val resumeTarget: TtsSessionPlanning.ReaderResumeTarget?,
        val devTarget: DevLaunchPlanning.DevStartTarget?,
    )

    private companion object {
        const val STATE_ROUTE_STACK = "navigation.route_stack"
        const val STATE_SCROLL_KEYS = "navigation.scroll_keys"
        const val STATE_SCROLL_VALUES = "navigation.scroll_values"
        const val NOTIFICATION_PERMISSION_PREFERENCES = "notification_permission"
        const val KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN = "automatic_prompt_shown"
    }
}

private fun MainActivity.showStartupLoading() {
    frame.removeAllViews()
    frame.addView(
        ProgressBar(this),
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        ),
    )
}

private fun MainActivity.showStartupFailure(error: Throwable) {
    Timber.e(error, "Repository startup failed")
    frame.removeAllViews()
    frame.addView(
        TextView(this).apply {
            text = "The library could not be loaded. Restart the app to try again."
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        },
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
}
