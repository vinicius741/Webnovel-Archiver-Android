package com.vinicius741.webnovelarchiver.navigation

import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.ui.FoldTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

enum class StoryOperationKind {
    EPUB,
    CLEANUP,
    SYNC,
}

data class StoryOperationState(
    val storyId: String,
    val kind: StoryOperationKind,
    val message: String,
    val progress: Float? = null,
)

/**
 * Transient UI state for the Add Story screen. Kept on [ScreenHost] so it survives the in-place
 * re-renders the screen performs while a fetch is in flight (mirroring [storyOperation] for the
 * Details screen). `status` is `null` when idle; a non-null value means a fetch is running.
 *
 * A mutable holder rather than two `var`s on the interface because the Add Story screen reads and
 * writes both fields together and Kotlin `var` interface properties backed by `MainActivity` fields
 * are clearer bundled into one owner.
 */
class AddStoryScreenState {
    var status: String? = null
    var urlText: String? = null
}

class UpdateTrackerScreenState {
    var syncing: Boolean = false
    var completed: Int = 0
    var total: Int = 0

    // One entry per story currently being synced. Bulk sync runs several stories concurrently, so a
    // single "current story" slot would be clobbered; this map tracks each in-flight story instead.
    // Insertion-ordered so progress text can show a stable representative story.
    val inFlight: MutableMap<String, InFlightStorySync> = linkedMapOf()
    val errors: MutableMap<String, String> = mutableMapOf()
    val syncedUpdatedChapterIds: MutableMap<String, List<String>> = mutableMapOf()

    fun reset(total: Int) {
        syncing = true
        completed = 0
        this.total = total
        inFlight.clear()
        errors.clear()
        syncedUpdatedChapterIds.clear()
    }

    fun finish() {
        syncing = false
        inFlight.clear()
    }
}

enum class BackupExportKind {
    JSON,
    FULL,
}

/** In-flight settings export state, retained across configuration-driven screen rebuilds. */
class BackupExportState {
    var activeKind: BackupExportKind? = null
}

/** Mutable progress holder for a story being synced in [UpdateTrackerScreenState.inFlight]. */
class InFlightStorySync(
    val title: String,
) {
    var status: String = "Starting..."
}

/**
 * Transient UI state for the Follow Updates selection screen. `query` is the live search text and
 * `showCovers` mirrors the persisted [com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences.showCoversOnUpdates]
 * toggle. Both live here (rather than only in the EditText) so the screen can re-render the
 * filtered list in place without losing the typed query, and so the toggle survives the
 * navigation/re-render cycle that rebuilding the view tree performs.
 */
class UpdateFollowSelectionState {
    var query: String = ""
    var showCovers: Boolean = false
}

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
     * Single-owner repository (R2). Screens read observable/cached state (library, queue, settings)
     * through this rather than re-reading JSON on every render (Speed S3 — disk reads off the render
     * path).
     */
    val repository: AppRepository
    val syncEngine: StorySyncEngine
    val downloadEngine: DownloadEngine
    val epubEngine: EpubEngine
    val ttsEngine: TtsEngine
    var activeStory: Story?
    var storyOperation: StoryOperationState?
    val navigator: AppNavigator

    /** Scroll offsets keyed by [AppRoute.stableKey], never by mutable app-bar copy. */
    val routeScrollPositions: MutableMap<String, Int>

    /** Renders an already-selected route, used by back navigation and saved-state restoration. */
    fun renderRoute(route: AppRoute)

    fun navigateBack() {
        navigator.back()?.let(::renderRoute)
    }

    /**
     * Transient state for the Add Story screen's inline fetch flow (status line + URL draft). See
     * [AddStoryScreenState]; lives here so it survives the screen's status-driven re-renders.
     */
    val addStoryScreenState: AddStoryScreenState

    val updateTrackerScreenState: UpdateTrackerScreenState

    val backupExportState: BackupExportState

    /**
     * Transient state for the Follow Updates selection screen's search field + show-covers toggle
     * (see [UpdateFollowSelectionState]). Lives here so the typed query and toggle survive the
     * screen's in-place list re-renders.
     */
    val updateFollowSelectionState: UpdateFollowSelectionState

    /**
     * Per-story expand/collapse choices the user has made on the Download Manager screen, keyed by
     * `storyId`. A story absent from the map defaults to expanded when it has active work or
     * failures, collapsed otherwise. Survives the screen's periodic re-renders (which rebuild the
     * whole tree) so a user's manual collapse isn't undone 30s later.
     */
    val storyExpandOverride: MutableMap<String, Boolean>

    /**
     * The current screen's in-app back navigation, set by [com.vinicius741.webnovelarchiver.ui.screen].
     * The system back button (wired in [com.vinicius741.webnovelarchiver.app.MainActivity]) invokes this so
     * the hardware/gesture back press mirrors the app-bar back arrow instead of closing the app. `null`
     * on the root (Library) screen, where back exits the app as usual.
     */
    var backHandler: (() -> Unit)?
    val frame: FrameLayout

    /**
     * Re-renders the screen that is currently on the [frame]. Each screen sets this to a lambda that
     * re-invokes its own `showXxx()` (which rebuilds the view tree), so fold/unfold/rotation and the
     * "Large Screen Layout" setting toggle can reflow the live screen in place. `null` until a screen
     * opts in.
     */
    var rerender: (() -> Unit)?

    /** Observer owned by the currently rendered screen. [screen] cancels it on navigation/rebuild. */
    var screenObserver: Job?

    /** Foldable hinge/inner-display detector (androidx.window). Read on every screen render. */
    val foldTracker: FoldTracker
    val importBackupLauncher: ActivityResultLauncher<Array<String>>
    val importFullBackupLauncher: ActivityResultLauncher<Array<String>>
}
