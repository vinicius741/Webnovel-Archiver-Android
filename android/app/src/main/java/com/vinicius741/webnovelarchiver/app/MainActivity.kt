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
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.app.renderRouteDispatch
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.browser.BrowserImportPlanning
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.importFromBrowser
import com.vinicius741.webnovelarchiver.feature.details.detachDetailsTtsListener
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.player.attachTtsMiniPlayer
import com.vinicius741.webnovelarchiver.feature.reader.detachReaderTtsListener
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showDataBackup
import com.vinicius741.webnovelarchiver.feature.settings.showNotifications
import com.vinicius741.webnovelarchiver.navigation.AddStoryScreenState
import com.vinicius741.webnovelarchiver.navigation.AiControlsScreenState
import com.vinicius741.webnovelarchiver.navigation.AppNavigator
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackupExportState
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    override val routeScrollPositions: MutableMap<String, Int> = mutableMapOf()
    override val addStoryScreenState: AddStoryScreenState = AddStoryScreenState()
    override val updateTrackerScreenState: UpdateTrackerScreenState = UpdateTrackerScreenState()
    override val backupExportState: BackupExportState = BackupExportState()
    override val aiControlsScreenState: AiControlsScreenState = AiControlsScreenState()

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
     * Always registered, enabled only while a screen provides in-app back navigation (see
     * [backHandler]'s setter); disabled on root so the OS predictive-back default applies.
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                backHandler?.invoke()
            }
        }

    override val notificationPermissionLauncher =
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
                showDataBackup()
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
        // Process-wide AppContainer: one storage, one network client, one set of engines shared with the services.
        val container = appContainer
        repository = container.repository
        syncEngine = container.syncEngine
        epubEngine = container.epubEngine
        // Control/enqueue handle only (ownsProcessLoop = false): the foreground service owns the
        // single process loop — two loops would each honor their own concurrency cap and double
        // the parallelism.
        downloadEngine =
            DownloadEngine(
                repository,
                container.network,
                container.downloadPacer,
                ownsProcessLoop = false,
            )
        // Same instance the TtsForegroundService plays through, so the reader's listener fires
        // for service-driven playback.
        ttsEngine = container.ttsEngine
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
        attachTtsMiniPlayer(root)
        // Background AI cover jobs outlive this activity. Attach only after `frame` exists: the
        // collectors run inline on Main.immediate and their first pass reads frame.tag.
        attachAiCoverJobBridge()
        attachAiChapterRewriteJobBridge()
        onBackPressedDispatcher.addCallback(this, backCallback)
        // First paint precedes hydration; seed from the hint written on every theme change / start.
        StartupThemeHint.read(this)?.let(ThemeManager::apply)
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
        StartupThemeHint.write(this, startupState.activeThemeId)
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
            renderRoute(devTarget)
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

    override fun renderRoute(route: AppRoute) = renderRouteDispatch(route)

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

    private fun browserImportUrl(intent: Intent?): String? = BrowserImportPlanning.importUrl(intent?.action, intent?.dataString)

    private data class InitialStartupState(
        val activeThemeId: String,
        val resumeTarget: TtsSessionPlanning.ReaderResumeTarget?,
        val devTarget: AppRoute?,
    )

    private companion object {
        const val STATE_ROUTE_STACK = "navigation.route_stack"
        const val STATE_SCROLL_KEYS = "navigation.scroll_keys"
        const val STATE_SCROLL_VALUES = "navigation.scroll_values"
    }
}
