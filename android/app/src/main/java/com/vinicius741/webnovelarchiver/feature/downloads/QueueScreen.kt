package com.vinicius741.webnovelarchiver.feature.downloads

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.feature.story.showCoverDialog
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.ShellDestination
import com.vinicius741.webnovelarchiver.navigation.openDestination
import com.vinicius741.webnovelarchiver.ui.APP_BAR_SUBTITLE_TAG
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.layout.queueMaxWidth
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.rememberListPosition
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill

/** The Downloads screen's model, kept while Downloads is in the history. */
private fun ScreenHost.queueModel(): QueueScreenModel = screenModels.get(AppRoute.Queue.stableKey) { QueueScreenModel(HostQueueData(this)) }

/**
 * The Downloads destination: the global queue in download order, as a summary card, Needs attention
 * cards, then Downloading now, Up next (drag to reorder), Paused and a folded Finished section.
 * Built from [QueueScreenModel]'s state; see [QueueRenderer] for how it follows it.
 */
internal fun ScreenHost.showQueue() {
    // Re-render on fold/unfold/rotation so the width cap re-centers for the new window.
    rerender = { showQueue() }
    // The app bar's actions depend on the state, so the model is looked up before screen() builds
    // the bar. screen() navigates to this route before it prunes the store, so the model is kept.
    val model = queueModel()
    QueueRenderer(this, model, model.onRender()).render()
}

/**
 * One render of the Downloads screen, bound to [model] while its views are on screen. A change of
 * the app bar's actions rebuilds the screen; every other state is submitted to the list, whose
 * DiffUtil rebinds only the rows that changed (a countdown tick rebinds one chapter row), and the
 * subtitle is patched in place.
 */
private class QueueRenderer(
    private val host: ScreenHost,
    private val model: QueueScreenModel,
    /** The state this render shows. */
    private var applied: QueueUiState,
) {
    private val app = host.app
    private val menus = QueueMenus(host, model)
    private val adapter = QueueListAdapter(app, ListActions()) { view, id -> bindCover(view, id) }
    private lateinit var emptySlot: FrameLayout
    private lateinit var list: RecyclerView

    fun render() {
        val layout = host.currentScreenLayout()
        host.screen(
            route = AppRoute.Queue,
            title = "Downloads",
            subtitle = applied.subtitle,
            actions = menus.appBarActions(applied.appBar),
        ) {
            // Width-capped centered column so tablets/Fold don't stretch edge-to-edge; no effect on phones.
            val centered = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val centeredShell =
                MaxWidthFrameLayout(context).apply {
                    maxContentWidthDp = queueMaxWidth(layout.widthClass)
                    addView(
                        centered,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER_HORIZONTAL,
                        ),
                    )
                }
            emptySlot = FrameLayout(context)
            list =
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    this.adapter = this@QueueRenderer.adapter
                    itemAnimator = null
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
            adapter.touchHelper.attachToRecyclerView(list)
            centered.addView(emptySlot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            centered.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            showList(applied)
            host.rememberListPosition(list)
            addView(centeredShell, verticalFill())
        }
        list.bindWhileAttached(host.scope, model.state) { apply(it) }
        list.bindWhileAttached(host.scope, model.messages) { host.toast(it) }
    }

    private fun apply(next: QueueUiState) {
        if (next == applied) return
        val rebuild = next.appBarKey != applied.appBarKey
        applied = next
        if (rebuild) {
            if (host.navigator.current == AppRoute.Queue) host.showQueue()
            return
        }
        setSubtitle(next.subtitle)
        showList(next)
    }

    private fun showList(state: QueueUiState) {
        if (state.empty) {
            list.visibility = View.GONE
            emptySlot.visibility = View.VISIBLE
            if (emptySlot.childCount == 0) {
                emptySlot.addView(
                    makeEmptyState(
                        app,
                        message = "Chapters you download will appear here while they download.",
                        title = "Nothing downloading",
                        iconRes = R.drawable.wna_download_done,
                        actionLabel = "Find new chapters",
                        onAction = { host.openDestination(ShellDestination.UPDATES) },
                    ),
                )
            }
        } else {
            list.visibility = View.VISIBLE
            emptySlot.visibility = View.GONE
        }
        adapter.submit(state.items)
    }

    /** Patches the app bar's subtitle in place, so a queue tick never rebuilds the screen for it. */
    private fun setSubtitle(text: String) {
        val subtitle = host.frame.findViewWithTag<TextView>(APP_BAR_SUBTITLE_TAG) ?: return
        if (subtitle.text != text) subtitle.text = text
    }

    /** Loads a novel's current cover into its card; tapping the cover opens it full screen. */
    private fun bindCover(
        view: ImageView,
        storyId: String,
    ) {
        val story = model.story(storyId)
        val source = story?.let { host.activeCoverSource(it) }
        if (source == null) {
            // The holder may be rebound from a novel whose cover is still loading; stop it landing here.
            view.dispose()
            view.setImageDrawable(null)
            view.setOnClickListener(null)
            view.isClickable = false
            return
        }
        view.setOnClickListener { model.story(storyId)?.let { host.showCoverDialog(it) } }
        host.loadImage(source, view, fallbackOnError = if (source is java.io.File) story.coverUrl?.takeIf { it.isNotBlank() } else null)
    }

    private inner class ListActions : QueueListActions {
        override fun onCheck(
            card: SourceCheckCard,
            trouble: Boolean,
        ) {
            if (trouble) {
                menus.showSourceCheckTrouble(card)
            } else {
                card.sampleUrl?.let { model.verifySite(card.providerId, it) }
            }
        }

        override fun onRetryFailed() = model.run(QueueControl.RetryFailed)

        override fun onToggleFinished() = model.toggleFinished()

        override fun onClearFinished() = menus.confirmClearFinished()

        override fun onToggleStory(storyId: String) = model.toggleStory(storyId)

        override fun onStoryPrimary(item: QueueStoryItem) = menus.runStoryAction(item.storyId, item.primaryAction)

        override fun onStoryMenu(item: QueueStoryItem) = menus.showStoryMenu(item)

        override fun onChapterRetry(item: QueueChapterItem) = model.run(QueueControl.RetryJob(item.jobId))

        override fun onChapterMenu(item: QueueChapterItem) = menus.showChapterMenu(item)

        override fun onReorder(
            upNextOrder: List<String>,
            downloadNow: String?,
        ) = model.reorder(upNextOrder, downloadNow)
    }
}
