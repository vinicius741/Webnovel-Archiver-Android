package com.vinicius741.webnovelarchiver

import android.Manifest
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
import com.vinicius741.webnovelarchiver.appContainer
import com.vinicius741.webnovelarchiver.core.AppStorage
import com.vinicius741.webnovelarchiver.core.DownloadEngine
import com.vinicius741.webnovelarchiver.core.EpubEngine
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StorySyncEngine
import com.vinicius741.webnovelarchiver.core.TtsEngine
import com.vinicius741.webnovelarchiver.core.TtsSessionPlanning
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App entry point. Owns lifecycle and wiring only: instantiates the storage/engines, the root
 * [frame], and the backup launchers, then hands the first screen off to the `ScreenHost`
 * extensions split across the `screens/`, `actions/`, and `ui/` files. All screen rendering,
 * navigation, and business actions live there — this class just implements [ScreenHost].
 */
class MainActivity : AppCompatActivity(), ScreenHost {
    override val app: AppCompatActivity get() = this
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override lateinit var storage: AppStorage
    override lateinit var syncEngine: StorySyncEngine
    override lateinit var downloadEngine: DownloadEngine
    override lateinit var epubEngine: EpubEngine
    override lateinit var ttsEngine: TtsEngine
    override lateinit var frame: FrameLayout
    override var activeStory: Story? = null
    override var storyOperation: StoryOperationState? = null
    override val storyExpandOverride: MutableMap<String, Boolean> = mutableMapOf()
    /** Re-render the screen currently on [frame]; set by each screen so config changes can reflow it. */
    override var rerender: (() -> Unit)? = null
    /** Foldable detector. Created in [onCreate] once engines/storage are up. */
    override lateinit var foldTracker: FoldTracker

    /**
     * The single system-back callback. Always registered, but enabled only while a screen has
     * provided in-app back navigation (see [backHandler]'s setter). Disabled on the root screen so
     * the OS default — exit to home, with the predictive-back home preview — applies unchanged.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            backHandler?.invoke()
        }
    }

    override var backHandler: (() -> Unit)? = null
        set(value) {
            field = value
            backCallback.isEnabled = value != null
        }

    override val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        scope.launch { toast(withContext(Dispatchers.IO) { storage.importBackupUri(uri) }); showSettings() }
    }

    override val importFullBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        scope.launch { toast(withContext(Dispatchers.IO) { storage.importFullBackupUri(uri) }); showLibrary() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pull process-wide dependencies from the AppContainer (M2): one AppStorage, one network
        // client, one set of engines shared with the foreground services (R3 single-owner).
        val container = appContainer
        storage = container.storage
        syncEngine = container.syncEngine
        epubEngine = container.epubEngine
        // The activity's download engine only enqueues/controls the queue (startNow=false); the
        // foreground service owns the actual process loop. They share the repository's txMutex.
        downloadEngine = DownloadEngine(storage, container.network, container.repository)
        ttsEngine = TtsEngine(this, storage)
        ThemeManager.apply(storage.getDisplayPreferences().activeThemeId)
        applyWindowTheme()
        downloadEngine.onChanged = { runOnUiThread { if (activeStory == null) showLibrary() else activeStory?.let { showDetails(it.id) } } }
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
        val resumeTarget = TtsSessionPlanning.readerResumeTarget(storage.getTtsSession()) { storyId ->
            storage.getStory(storyId)
        }
        if (resumeTarget != null) {
            showReader(resumeTarget.storyId, resumeTarget.chapterId)
        } else {
            showLibrary()
        }
    }

    override fun onDestroy() {
        ttsEngine.shutdown()
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
}
