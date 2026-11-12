package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.navigation.AppRoute
import java.util.Locale

/** The story page's tabs, in strip order. Each one is addressed by its own [AppRoute]. */
enum class StoryTab(
    val label: String,
) {
    OVERVIEW("Overview"),
    CHAPTERS("Chapters"),
    AUDIO("Audio"),
    TRENDS("Trends"),
    AI("AI"),
}

/** Which tab a route shows, the route a tab opens, and what each tab's strip label reads. */
object StoryTabPlanning {
    /** The tab [route] shows, or null when it is not a story page route. */
    fun tabOf(route: AppRoute): StoryTab? =
        when (route) {
            is AppRoute.Details -> if (route.chapters) StoryTab.CHAPTERS else StoryTab.OVERVIEW
            is AppRoute.NarrationStory -> StoryTab.AUDIO
            is AppRoute.Trends -> StoryTab.TRENDS
            is AppRoute.AiControls -> StoryTab.AI
            else -> null
        }

    /** The story a story page route belongs to, or null for any other route. */
    fun storyIdOf(route: AppRoute): String? =
        when (route) {
            is AppRoute.Details -> route.storyId
            is AppRoute.NarrationStory -> route.storyId
            is AppRoute.Trends -> route.storyId
            is AppRoute.AiControls -> route.storyId
            else -> null
        }

    /** The route that opens [tab] for [storyId], with no focus or chapter preselected. */
    fun routeFor(
        tab: StoryTab,
        storyId: String,
    ): AppRoute =
        when (tab) {
            StoryTab.OVERVIEW -> AppRoute.Details(storyId)
            StoryTab.CHAPTERS -> AppRoute.Details(storyId, chapters = true)
            StoryTab.AUDIO -> AppRoute.NarrationStory(storyId)
            StoryTab.TRENDS -> AppRoute.Trends(storyId)
            StoryTab.AI -> AppRoute.AiControls(storyId)
        }

    /** On the inner screen the hero has its own pane, so the Overview tab holds only the About part. */
    fun label(
        tab: StoryTab,
        twoPane: Boolean,
    ): String = if (twoPane && tab == StoryTab.OVERVIEW) "About" else tab.label

    /** The small count beside a tab's label: chapters, and generated audio once there is some. */
    fun count(
        tab: StoryTab,
        chapters: Int,
        generatedAudio: Int,
    ): String? =
        when (tab) {
            StoryTab.CHAPTERS -> chapters.takeIf { it > 0 }?.let(::compactCount)
            StoryTab.AUDIO -> generatedAudio.takeIf { it > 0 }?.let(::compactCount)
            else -> null
        }

    /** The cover, title and contextual actions show on the Overview tab, and beside every tab on the inner screen. */
    fun showsHero(
        tab: StoryTab,
        twoPane: Boolean,
    ): Boolean = twoPane || tab == StoryTab.OVERVIEW

    private const val THOUSAND = 1_000
    private const val COMPACT_LIMIT = 10_000

    /** Exact below 10,000 so chapter counts read precisely; "12.3K" above. */
    fun compactCount(value: Int): String =
        if (value < COMPACT_LIMIT) {
            String.format(Locale.US, "%,d", value)
        } else {
            val tenths = value / (THOUSAND / 10)
            if (tenths % 10 == 0) "${tenths / 10}K" else "${tenths / 10}.${tenths % 10}K"
        }
}
