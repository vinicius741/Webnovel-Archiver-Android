package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/*
 * The story page's renderer side: one render of a story tab, bound to the story's
 * [StoryPageScreenModel] while the render is on screen. Download events patch the hero and the
 * chapter rows in place, operation progress patches its slot, and an operation starting or ending
 * redraws what depends on it, without rebuilding the page for progress ticks.
 */

/** The story page's model, shared by all the story's tabs: keyed by the Overview route, which every tab's history entry retains. */
internal fun ScreenHost.storyPageModel(storyId: String): StoryPageScreenModel =
    screenModels.get(AppRoute.Details(storyId).stableKey) {
        val container = app.appContainer
        val attention = container.aiAttention
        StoryPageScreenModel(
            storyId = storyId,
            data = RepositoryStoryPageData(repository),
            pacingSnapshots = container.downloadPacer.snapshots,
            ticker = tickerFlow(nowMillis = container.time::nowMillis),
            nowMillis = container.time::nowMillis,
            operations = operations,
            coverToReview = { id -> id in repository.aiCoverUnseen.value || id in attention.state.value.coverDrafts },
            coverReviewChanges = merge(repository.aiCoverUnseen, attention.state),
        )
    }

/** The slim strip a tab without the hero shows under the tab strip for a running sync / cleanup / EPUB; gone while idle. */
internal fun buildOperationStrip(
    context: Context,
    operation: StoryOperationState?,
): LinearLayout {
    val side = context.dp(SCREEN_SIDE_PADDING_DP)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(side, context.dp(Spacing.SM), side, 0)
        renderOperationStrip(this, operation)
    }
}

private fun renderOperationStrip(
    strip: LinearLayout,
    operation: StoryOperationState?,
) {
    if (operation == null) {
        strip.removeAllViews()
        strip.visibility = View.GONE
    } else {
        strip.visibility = View.VISIBLE
        renderStoryOperationProgress(strip, operation)
    }
}

/**
 * Keeps one render of a story tab in step with [model]. It collects the model's state from
 * [start] until [root] leaves the window (tab switch, fold, Back), so a stale render is never
 * patched.
 *
 * - An operation ending re-renders the tab: its results (new chapters, a cleaned text, an EPUB)
 *   reach every part of it.
 * - An operation starting, or a finished AI cover starting or stopping to wait for review, redraws what shows it: the
 *   Overview re-renders, a tab beside the inner screen's hero pane rebuilds only the pane, and a
 *   tab without the hero updates its operation strip.
 * - Progress ticks patch the progress slots; download events patch the hero's actions, banner and
 *   coverage and the chapter rows, waiting while the chapter list is dragged or flung.
 */
internal class StoryPageRenderer(
    private val host: ScreenHost,
    private val model: StoryPageScreenModel,
    private val root: View,
    private val tab: StoryTab,
    private var hero: StoryHero?,
    /** The inner screen's hero pane, when this render has one. */
    private val heroPane: ViewGroup?,
    private val chapters: ChaptersTab?,
    /** The operation strip of a tab without the hero. */
    private val strip: LinearLayout?,
    /** The About part's AI description progress, on the Overview. */
    private val aboutAiSlot: LinearLayout?,
    /** The state this render shows. */
    private var applied: StoryPageUiState,
) {
    private var downloadPatchPending = false

    fun start() {
        root.bindWhileAttached(host.scope, model.state) { apply(it) }
    }

    private fun apply(next: StoryPageUiState) {
        val previous = applied
        if (!root.isAttachedToWindow || next == previous) return
        applied = next
        if (previous.content != null && next.content == null) {
            host.renderRoute(host.navigator.current)
            return
        }
        val contentStarted = next.content != null && next.content.kind != previous.content?.kind
        val aiStarted = next.ai != null && next.ai.kind != previous.ai?.kind
        if ((contentStarted || aiStarted || next.coverToReview != previous.coverToReview) && redrawOperations(next)) return
        next.content?.takeIf { it != previous.content }?.let { operation ->
            (hero?.operationSlot ?: strip)?.let { renderStoryOperationProgress(it, operation) }
        }
        if (next.ai != previous.ai) patchAi(next.ai)
        if (next.downloadChangedSince(previous)) {
            scheduleDownloadPatch()
        } else if (next.pacing != previous.pacing) {
            patchBanner(next)
        }
    }

    /** Redraws the parts that show operations; true when the whole tab was re-rendered (this render is gone). */
    private fun redrawOperations(state: StoryPageUiState): Boolean {
        val pane = heroPane
        when {
            hero == null -> strip?.let { renderOperationStrip(it, state.content) }
            tab != StoryTab.OVERVIEW && pane != null -> rebuildHero(pane, state)
            else -> {
                host.renderRoute(host.navigator.current)
                return true
            }
        }
        return false
    }

    /** Rebuilds only the hero pane, so the tab beside it keeps its list, scroll and loaded content. */
    private fun rebuildHero(
        pane: ViewGroup,
        state: StoryPageUiState,
    ) {
        val story = state.story ?: return
        host.activeStory = story
        val rebuilt = host.buildStoryHero(story, tab, twoPane = true, state)
        val scrollY = pane.scrollY
        pane.removeAllViews()
        pane.addView(rebuilt.view)
        pane.post { pane.scrollTo(0, scrollY) }
        hero = rebuilt
    }

    /** Patches the AI job's progress, or empties its slots once it ends (a waiting cover redraws through [StoryPageUiState.coverToReview]). */
    private fun patchAi(operation: StoryOperationState?) {
        val slots = listOfNotNull(hero?.aiSlot, aboutAiSlot)
        if (operation == null) {
            slots.forEach {
                it.removeAllViews()
                it.visibility = View.GONE
            }
            return
        }
        val slot = if (operation.kind == StoryOperationKind.AI_COVER) hero?.aiSlot else aboutAiSlot
        slot?.let { renderStoryOperationProgress(it, operation) }
    }

    /** Coalesces download events into one patch, held while the chapter list is dragged or flung. */
    private fun scheduleDownloadPatch() {
        if (downloadPatchPending) return
        downloadPatchPending = true
        root.post(
            object : Runnable {
                override fun run() {
                    if (!root.isAttachedToWindow) return
                    if (chapters != null && chapters.list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        root.postDelayed(this, DETAILS_SCROLL_RETRY_MS)
                        return
                    }
                    downloadPatchPending = false
                    patchDownloads(applied)
                }
            },
        )
    }

    private fun patchDownloads(state: StoryPageUiState) {
        val story = state.story ?: return
        hero?.let { hero ->
            hero.coverage?.render(story)
            host.renderDetailsCta(hero.ctaSlot, story, state.download, state.isBusy)
            host.renderHeroDownloadBanner(hero.bannerSlot, story, state.download, state.pacing)
        }
        chapters?.refresh?.invoke(state)
    }

    /** Only the banner's pacing line ticks; actions and rows follow download events. */
    private fun patchBanner(state: StoryPageUiState) {
        val hero = hero ?: return
        val story = state.story ?: return
        if (shouldShowDetailsBanner(state.download)) host.renderHeroDownloadBanner(hero.bannerSlot, story, state.download, state.pacing)
    }
}
