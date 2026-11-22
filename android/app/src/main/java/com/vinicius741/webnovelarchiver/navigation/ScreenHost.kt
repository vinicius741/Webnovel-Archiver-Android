package com.vinicius741.webnovelarchiver.navigation

import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.settings.BackupExports
import com.vinicius741.webnovelarchiver.feature.updates.UpdatesSyncs
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import kotlinx.coroutines.CoroutineScope

/**
 * The contract between [MainActivity] and the screen/action extension functions split across
 * the `screens/`, `actions/`, and `ui/` files. Exposes only the shared dependencies and the
 * root view — everything else (navigation, business actions, the view DSL, shared helpers)
 * lives as `internal` extension functions on this type (or on `ViewGroup`/`AppCompatActivity`).
 *
 * Screens reference each other freely: inside any `internal fun ScreenHost.showXxx()` body,
 * `this` is a `ScreenHost`, and Kotlin's implicit-receiver chain keeps it visible inside the
 * nested `LinearLayout.() -> Unit` screen blocks, so unqualified calls like `showDetails(id)`
 * resolve exactly as they did when everything was a member of `MainActivity`.
 */
interface ScreenHost {
    val app: AppCompatActivity
    val scope: CoroutineScope

    /**
     * Single-owner repository. Screens read observable/cached state (library, queue, settings)
     * through this rather than re-reading JSON on every render — disk reads stay off the render
     * path.
     */
    val repository: AppRepository
    val syncEngine: StorySyncEngine
    val downloadEngine: DownloadEngine
    val epubEngine: EpubEngine
    val ttsEngine: TtsEngine
    var activeStory: Story?

    /** Sync / cleanup / EPUB and AI work in flight on a story, observed by every screen that shows it. */
    val operations: StoryOperations

    /** One [ScreenModel] per history entry; see android/AGENTS.md, "Screen architecture". */
    val screenModels: ScreenModelStore

    val navigator: AppNavigator

    /** Per-history-entry scroll, list, and search memory, keyed by [AppRoute.stableKey] (see [RouteViewMemory]). */
    val routeMemory: RouteViewMemory

    /** Renders an already-selected route, used by back navigation and saved-state restoration. */
    fun renderRoute(route: AppRoute)

    /** Performs the notification action behind the Settings "Notifications" row. */
    fun performNotificationPermissionAction()

    /** Shows the one-time contextual Android 13+ permission request before a user-started download. */
    fun requestNotificationPermissionForDownload()

    fun navigateBack() {
        navigator.back()?.let(::renderRoute)
    }

    /** The full-backup export, which outlives Data & backup (see [BackupExports]). */
    val backupExports: BackupExports

    /** The Updates syncs (a row's sync, "Sync all") and the forecast cache, which outlive the Updates screen. */
    val updatesSyncs: UpdatesSyncs

    /**
     * What system Back does on the current screen, set by [com.vinicius741.webnovelarchiver.ui.screen]
     * (see [ScreenBack]). `null` before the first screen renders.
     */
    var screenBack: ScreenBack?

    /**
     * Re-syncs the system Back callback with [screenBack]. Screens call it when an in-screen state that
     * Back clears (a [BackIntercept]) starts or stops applying, so the Library root only claims Back
     * while it has something to clear and otherwise leaves it to Android's predictive back-to-home.
     */
    fun refreshBackCallback()

    val frame: FrameLayout

    /** The app frame around [frame]: docked mini player, navigation bar / rail, and their badges. */
    val shell: com.vinicius741.webnovelarchiver.ui.AppShell

    /**
     * Re-renders the screen that is currently on the [frame]. Each screen sets this to a lambda that
     * re-invokes its own `showXxx()` (which rebuilds the view tree), so fold/unfold/rotation and the
     * "Large Screen Layout" setting toggle can reflow the live screen in place. `null` until a screen
     * opts in.
     */
    var rerender: (() -> Unit)?

    /**
     * Invoked at the end of every [com.vinicius741.webnovelarchiver.ui.screen] build so app-level
     * chrome that depends on the current route (the TTS mini-player) can refresh without owning a
     * second navigation observer.
     */
    var onScreenBuilt: (() -> Unit)?

    /** Foldable hinge/inner-display detector (androidx.window). Read on every screen render. */
    val foldTracker: FoldTracker
    val importFullBackupLauncher: ActivityResultLauncher<Array<String>>
    val notificationPermissionLauncher: ActivityResultLauncher<String>
}

/** The running content operation (sync / cleanup / EPUB), read-only; change it through [ScreenHost.operations]. */
val ScreenHost.storyOperation: StoryOperationState? get() = operations.content.value

/** The running AI job (cover / description), read-only; change it through [ScreenHost.operations]. */
val ScreenHost.aiOperation: StoryOperationState? get() = operations.ai.value
