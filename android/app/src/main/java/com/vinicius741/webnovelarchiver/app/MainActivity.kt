package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.browser.BrowserImportPlanning
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.importFromBrowser
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.library.showAddStory
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.reader.detachReaderTtsListener
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.AddStoryScreenState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    override lateinit var storage: AppStorage
    override lateinit var repository: AppRepository
    override lateinit var syncEngine: StorySyncEngine
    override lateinit var downloadEngine: DownloadEngine
    override lateinit var epubEngine: EpubEngine
    override lateinit var ttsEngine: TtsEngine
    override lateinit var frame: FrameLayout
    override var activeStory: Story? = null
    override var storyOperation: StoryOperationState? = null
    override val addStoryScreenState: AddStoryScreenState = AddStoryScreenState()
    override val updateTrackerScreenState: UpdateTrackerScreenState = UpdateTrackerScreenState()

    // Lazy: seeded from persisted DisplayPreferences, which are only available after `storage` is
    // assigned in onCreate. First access happens during a screen render, well after that.
    override val updateFollowSelectionState: UpdateFollowSelectionState by lazy {
        UpdateFollowSelectionState().apply {
            showCovers = storage.getDisplayPreferences().showCoversOnUpdates
        }
    }
    override val storyExpandOverride: MutableMap<String, Boolean> = mutableMapOf()

    /** Re-render the screen currently on [frame]; set by each screen so config changes can reflow it. */
    override var rerender: (() -> Unit)? = null
    override var screenObserver: Job? = null

    /** Foldable detector. Created in [onCreate] once engines/storage are up. */
    override lateinit var foldTracker: FoldTracker

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

    override var backHandler: (() -> Unit)? = null
        set(value) {
            field = value
            backCallback.isEnabled = value != null
        }

    override val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            scope.launch {
                toast(withContext(Dispatchers.IO) { storage.importBackupUri(uri) })
                showSettings()
            }
        }

    override val importFullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            scope.launch {
                toast(withContext(Dispatchers.IO) { storage.importFullBackupUri(uri) })
                showLibrary()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pull process-wide dependencies from the AppContainer (M2): one AppStorage, one network
        // client, one set of engines shared with the foreground services (R3 single-owner).
        val container = appContainer
        storage = container.storage
        repository = container.repository
        syncEngine = container.syncEngine
        epubEngine = container.epubEngine
        // The activity's download engine only enqueues/controls the queue (startNow=false); the
        // foreground service owns the actual process loop. They share one AppStorage, so queue
        // read-modify-writes serialize on its monitor (R3 single-owner).
        downloadEngine = DownloadEngine(repository, container.network)
        // Shared process-wide TTS engine (M2): the same instance the TtsForegroundService plays
        // through, so the reader's multicast state listener fires for service-driven playback.
        ttsEngine = container.ttsEngine
        ThemeManager.apply(storage.getDisplayPreferences().activeThemeId)
        applyWindowTheme()
        frame = FrameLayout(this)
        setContentView(frame)
        onBackPressedDispatcher.addCallback(this, backCallback)
        requestNotificationPermissionIfNeeded()
        // Foldable hinge/inner-display detection. The activity declares all configChanges in the
        // manifest, so fold/unfold/rotation does NOT recreate it — we must observe the fold sensor
        // (here) and re-render the live screen on change (below) for the responsive layout to adapt.
        foldTracker = FoldTracker(this, scope)
        scope.launch {
            foldTracker.isFoldingFeature.collect { runOnUiThread { rerender?.invoke() } }
        }
        val browserImportUrl = browserImportUrl(intent)
        val resumeTarget =
            TtsSessionPlanning.readerResumeTarget(storage.getTtsSession()) { storyId ->
                storage.getStory(storyId)
            }
        // Dev-only "launch straight into a screen" override (agent QA). Gated on BuildConfig.DEBUG so
        // it is dead in release; see DevLaunchPlanning. Highest precedence: a valid dev_start_screen
        // extra wins over browser import and TTS resume so the agent reliably lands where it asked.
        val devTarget =
            if (BuildConfig.DEBUG) {
                DevLaunchPlanning.resolve(
                    screenName = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_SCREEN),
                    storyOverride = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_STORY),
                    chapterOverride = intent.getStringExtra(DevLaunchPlanning.EXTRA_DEV_START_CHAPTER),
                    // Lazy: only read the library for reader/details targets.
                    libraryProvider = { storage.getLibrary() },
                )
            } else {
                null
            }
        if (devTarget != null) {
            when (devTarget) {
                DevLaunchPlanning.DevStartTarget.Library -> showLibrary()
                DevLaunchPlanning.DevStartTarget.Queue -> showQueue()
                DevLaunchPlanning.DevStartTarget.Settings -> showSettings()
                DevLaunchPlanning.DevStartTarget.Updates -> showUpdates()
                DevLaunchPlanning.DevStartTarget.AddStory -> showAddStory()
                is DevLaunchPlanning.DevStartTarget.Reader ->
                    showReader(devTarget.storyId, devTarget.chapterId)
                is DevLaunchPlanning.DevStartTarget.Details -> showDetails(devTarget.storyId)
            }
        } else if (browserImportUrl != null) {
            showLibrary()
            importFromBrowser(browserImportUrl)
        } else if (resumeTarget != null) {
            showReader(resumeTarget.storyId, resumeTarget.chapterId)
        } else {
            showLibrary()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        browserImportUrl(intent)?.let { url ->
            showLibrary()
            importFromBrowser(url)
        }
    }

    override fun onResume() {
        super.onResume()
        SourceAccessRetryCoordinator.consumeReadyRetry()?.invoke()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
    }

    private fun browserImportUrl(intent: Intent?): String? = BrowserImportPlanning.importUrl(intent?.action, intent?.dataString)
}
