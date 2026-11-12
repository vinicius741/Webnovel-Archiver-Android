package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.feature.story.StoryTabPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp

/*
 * How the story page follows a running sync / cleanup / EPUB without rebuilding the tab the user
 * is on: the Overview re-renders, the inner screen's hero pane rebuilds alone, and a tab without
 * the hero shows the operation in a slim strip under its tabs.
 */

/**
 * Refreshes the story page after an operation started or ended, so the Sync / EPUB state and
 * progress appear: the Overview re-renders (its About part follows the busy state too), a tab
 * beside the inner-screen pane rebuilds only the pane, and a tab without the hero updates the
 * operation strip under its tabs. Other screens are left alone.
 */
internal fun ScreenHost.refreshStoryHero(storyId: String) {
    if (!storyTabShowing(storyId)) return
    val current = navigator.current
    val tab = StoryTabPlanning.tabOf(current) ?: return
    when {
        !StoryTabPlanning.showsHero(tab, currentScreenLayout().isTwoPane) -> refreshStoryOperationStrip(storyId)
        tab != StoryTab.OVERVIEW && rebuildHeroPane(storyId, tab) -> Unit
        else -> renderRoute(current)
    }
}

/** The slim operation slot a tab without the hero shows under the tab strip; empty and gone while idle. */
internal fun ScreenHost.buildOperationStrip(storyId: String): LinearLayout {
    val side = dp(SCREEN_SIDE_PADDING_DP)
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        tag = OPERATION_STRIP_TAG
        setPadding(side, dp(Spacing.SM), side, 0)
        renderOperationStrip(this, storyId)
    }
}

/** Shows [storyId]'s running operation in [strip] (and makes it the slot ticks patch), or hides it. */
private fun ScreenHost.renderOperationStrip(
    strip: LinearLayout,
    storyId: String,
) {
    val operation =
        storyOperation?.takeIf {
            it.storyId == storyId && it.kind != StoryOperationKind.AI_COVER && it.kind != StoryOperationKind.AI_DESCRIPTION
        }
    if (operation == null) {
        strip.removeAllViews()
        strip.visibility = View.GONE
        if (detailsOperationSlot === strip) detailsOperationSlot = null
        return
    }
    strip.visibility = View.VISIBLE
    renderStoryOperationProgress(strip, operation)
    detailsOperationSlot = strip
}

/** Re-reads the operation into the strip under the tab strip, when one of [storyId]'s tabs shows it. */
internal fun ScreenHost.refreshStoryOperationStrip(storyId: String) {
    if (!storyTabShowing(storyId)) return
    frame.findViewWithTag<LinearLayout>(OPERATION_STRIP_TAG)?.let { renderOperationStrip(it, storyId) }
}

/**
 * Rebuilds only the inner screen's hero pane, so the tab beside it keeps its list, scroll and
 * loaded content. False when no pane is on screen.
 */
private fun ScreenHost.rebuildHeroPane(
    storyId: String,
    tab: StoryTab,
): Boolean {
    val pane = frame.findViewWithTag<HeroPane>(HERO_PANE_TAG) ?: return false
    val story = repository.story(storyId) ?: return false
    activeStory = story
    // The old hero's slots leave the screen with it.
    detailsOperationSlot = null
    detailsAiOperationSlot = null
    val hero = buildStoryHero(story, tab, twoPane = true)
    val scrollY = pane.scrollY
    pane.removeAllViews()
    pane.addView(hero.view)
    pane.post { pane.scrollTo(0, scrollY) }
    pane.bindings?.hero = hero
    return true
}

internal const val HERO_PANE_TAG = "story-hero-pane"
private const val OPERATION_STRIP_TAG = "story-operation-strip"
