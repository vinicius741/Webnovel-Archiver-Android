package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.feature.story.StoryTabPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple

/*
 * The story page: one destination whose tabs (Overview · Chapters · Audio · Trends · AI) are each
 * their own route. [storyScreen] wraps the app scaffold with the story's app bar, the tab strip
 * that stays put under it, and on the inner screen the hero (cover, title, actions) in a pane of
 * its own beside the tab. Switching tabs replaces the history entry ([switchStoryTab]).
 */

/** What a tab gets to fill: [hero] is set when this render shows the hero (Overview, or any tab beside the pane). */
internal class StoryFrame(
    val tab: StoryTab,
    val twoPane: Boolean,
    val hero: StoryHero?,
    /** Docked under the tab body, outside its scroller (the description player bar). */
    val footer: LinearLayout,
) {
    /** The Chapters tab, so live download events can refresh its rows, chips and count. */
    var chaptersTab: ChaptersTab? = null
}

/** The inner screen's hero pane; [bindings] lets a rebuilt hero take over the live download patches. */
internal class HeroPane(
    context: Context,
) : NestedScrollView(context) {
    var bindings: DetailsBindings? = null
}

/** One-shot and remembered choices the story page carries between its tab renders. */
internal object StoryTabMemory {
    /** Story whose Chapters tab should focus its search when it next opens (the app-bar search). */
    var focusChapterSearch: String? = null

    /** Chapter filters the settings file cannot store ("Not downloaded"), per story, for this run of the app. */
    val chapterFilters = HashMap<String, String>()
}

@Suppress("LongParameterList", "LongMethod") // Scaffold wrapper: call sites pass named arguments, one linear layout build.
internal fun ScreenHost.storyScreen(
    route: AppRoute,
    story: Story,
    scrollable: Boolean,
    title: String? = null,
    actions: List<AppBarAction>? = null,
    closeAction: (() -> Unit)? = null,
    backIntercept: BackIntercept? = null,
    block: LinearLayout.(StoryFrame) -> Unit,
) {
    val tab = requireNotNull(StoryTabPlanning.tabOf(route)) { "Not a story tab: ${route.name}" }
    // Opening another tab of the story already on screen (a job chip, the Listen button) is a tab
    // switch too: it replaces the entry, so Back still leaves the story.
    val current = navigator.current
    if (current.stableKey != route.stableKey && StoryTabPlanning.storyIdOf(current) == story.id) navigator.replace(route)
    activeStory = story
    // The Overview's description player re-attaches its collector; other tabs must not keep it.
    detachDetailsTtsListener()
    val twoPane = currentScreenLayout().isTwoPane
    // Cleared before rebuild so a stale slot cannot be patched after the tree is replaced.
    detailsOperationSlot = null
    detailsAiOperationSlot = null
    var frameRef: StoryFrame? = null
    screen(
        route = route,
        title = title ?: story.title,
        subtitle = "by ${story.author}",
        actions = actions ?: storyAppBarActions(story),
        scrollable = false,
        closeAction = closeAction,
        backIntercept = backIntercept,
        fullWidthBody = true,
    ) {
        // The tab strip sits flush under the app bar, so the scaffold's own body padding is dropped.
        setPadding(0, 0, 0, 0)
        val hero = if (StoryTabPlanning.showsHero(tab, twoPane)) buildStoryHero(story, tab, twoPane) else null
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = StoryFrame(tab, twoPane, hero, footer)
        frameRef = frame
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildStoryTabStrip(story, tab, twoPane))
        // Without the hero, a running sync / cleanup / EPUB shows in a slim slot under the strip.
        if (hero == null) column.addView(buildOperationStrip(story.id))
        val body = storyTabBody(scrollable, hero.takeIf { !twoPane }) { block(frame) }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(footer)
        if (twoPane && hero != null) {
            val pane =
                HeroPane(context).apply {
                    tag = HERO_PANE_TAG
                    isVerticalScrollBarEnabled = false
                    addView(hero.view)
                }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pane, LinearLayout.LayoutParams(dp(HERO_PANE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(
                        View(context).apply {
                            setBackgroundColor(ThemeManager.colors.line)
                        },
                        LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
    val frame = frameRef ?: return
    if (scrollable) {
        val saved = routeMemory.scrollPositions[navigator.current.stableKey] ?: 0
        com.vinicius741.webnovelarchiver.ui.findScrollView(this.frame)?.let { scroller ->
            if (saved > 0) scroller.post { scroller.scrollTo(0, saved) }
        }
    }
    if (frame.hero != null || frame.chaptersTab != null) {
        val bindings = DetailsBindings(this.frame.getChildAt(0), frame.chaptersTab, frame.hero)
        this.frame.findViewWithTag<HeroPane>(HERO_PANE_TAG)?.bindings = bindings
        observeDetailsDownload(story.id, bindings)
    }
}

/** The tab's body: a scroller (the hero leads it on the compact Overview), or a plain column for a list tab. */
private fun ScreenHost.storyTabBody(
    scrollable: Boolean,
    leadingHero: StoryHero?,
    fill: LinearLayout.() -> Unit,
): View {
    val side = dp(SCREEN_SIDE_PADDING_DP)
    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, if (leadingHero == null) dp(Spacing.MD) else 0, side, if (scrollable) dp(Spacing.XL) else 0)
            fill()
        }
    if (!scrollable) return content
    val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    leadingHero?.let { column.addView(it.view) }
    column.addView(content)
    return ScrollView(app).apply {
        isFillViewport = true
        tag = SCROLL_BODY_VIEW_TAG
        clipToPadding = false
        addView(column)
    }
}

/** The strip of tabs under the app bar: label, a small count, and a gold bar under the open one. */
private fun ScreenHost.buildStoryTabStrip(
    story: Story,
    current: StoryTab,
    twoPane: Boolean,
): View {
    val colors = ThemeManager.colors
    val audio = runCatching { narration.store.packsFor(story.id).size }.getOrDefault(0)
    val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    StoryTab.entries.forEach { tab ->
        val on = tab == current
        val label = StoryTabPlanning.label(tab, twoPane)
        val count = StoryTabPlanning.count(tab, story.chapters.size, audio)
        val texts =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    makeText(app, label, Type.LABEL_LARGE, if (on) colors.primary else colors.onSurfaceVariant).apply {
                        typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
                    },
                )
                count?.let {
                    addView(
                        makeText(app, it, Type.LABEL_SMALL, colors.faint).apply { setPadding(dp(6), 0, 0, 0) },
                    )
                }
            }
        val indicator =
            View(app).apply {
                background = if (on) roundedBg(colors.primary, dp(2).toFloat()) else null
            }
        val item =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                isSelected = on
                contentDescription = listOfNotNull(label, count).joinToString(" ") + if (on) ", selected" else ""
                background = selectableRipple(colors.onSurface)
                setPadding(dp(TAB_SIDE_PADDING_DP), dp(Spacing.MD), dp(TAB_SIDE_PADDING_DP), 0)
                addView(texts)
                addView(
                    indicator,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_INDICATOR_DP)).apply {
                        topMargin =
                            dp(Spacing.MD - 1)
                    },
                )
                setOnClickListener { if (!on) switchStoryTab(story.id, tab) }
            }
        row.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val scroller =
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(Spacing.XS), 0, dp(Spacing.XS), 0)
            clipToPadding = false
            addView(row)
        }
    // Keep the open tab in view on a narrow screen (AI sits at the far end).
    scroller.post {
        val selected = (0 until row.childCount).map(row::getChildAt).firstOrNull { it.isSelected } ?: return@post
        if (selected.right > scroller.width) scroller.scrollTo(selected.right - scroller.width + dp(Spacing.LG), 0)
    }
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.background)
        addView(scroller)
        addView(View(app).apply { setBackgroundColor(colors.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }
}

/** Search jumps to the Chapters tab's search; More opens the story menu. */
internal fun ScreenHost.storyAppBarActions(story: Story): List<AppBarAction> =
    listOf(
        AppBarAction(R.drawable.wna_search, "Search chapters") {
            StoryTabMemory.focusChapterSearch = story.id
            if (navigator.current == AppRoute.Details(story.id, chapters = true)) {
                renderRoute(navigator.current)
            } else {
                switchStoryTab(story.id, StoryTab.CHAPTERS)
            }
        },
        AppBarAction(R.drawable.wna_more_vert, "More options") { showDetailsOverflow(story) },
    )

/** Opens [tab] in place of the current tab, so Back leaves the story instead of walking its tabs. */
internal fun ScreenHost.switchStoryTab(
    storyId: String,
    tab: StoryTab,
    route: AppRoute = StoryTabPlanning.routeFor(tab, storyId),
) {
    val current = navigator.current
    if (StoryTabPlanning.storyIdOf(current) == storyId) navigator.replace(route) else navigator.navigate(route)
    renderRoute(route)
}

/** True while one of [storyId]'s tabs is the rendered screen. */
internal fun ScreenHost.storyTabShowing(storyId: String): Boolean {
    val current = navigator.current
    return StoryTabPlanning.storyIdOf(current) == storyId && frame.tag == current.stableKey
}

/**
 * Re-renders the story page after an operation finished: the tab the user is on when it is this
 * story's, otherwise the Overview (as before tabs existed).
 */
internal fun ScreenHost.showStoryScreen(storyId: String) {
    if (storyTabShowing(storyId)) renderRoute(navigator.current) else showDetails(storyId)
}

/**
 * Back to the story after an action on one of its sub-screens (a chapter selection queued): Back
 * when the story tab is right below, so the history keeps one entry for the story.
 */
internal fun ScreenHost.returnToStory(storyId: String) {
    val history = navigator.history
    val below = history.getOrNull(history.size - 2)
    when {
        storyTabShowing(storyId) -> renderRoute(navigator.current)
        below != null && StoryTabPlanning.storyIdOf(below) == storyId -> navigateBack()
        else -> showDetails(storyId)
    }
}

private const val HERO_PANE_WIDTH_DP = 330
private const val TAB_SIDE_PADDING_DP = 14
private const val TAB_INDICATOR_DP = 3
