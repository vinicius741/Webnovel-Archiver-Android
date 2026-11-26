package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

/**
 * The story page's Overview tab (the hero, then About), or its Chapters tab when [chapters] is set.
 * Both draw from the story's [StoryPageScreenModel]; [StoryPageRenderer] keeps them live.
 */
internal fun ScreenHost.showDetails(
    storyId: String,
    chapters: Boolean = false,
) {
    val story = repository.story(storyId) ?: return showLibrary()
    val route = AppRoute.Details(story.id, chapters)
    rerender = { showDetails(storyId, chapters) }
    var chaptersTab: ChaptersTab? = null
    storyScreen(
        route = route,
        story = story,
        // The chapter RecyclerView must be the scroll surface; a ScrollView wrapper defeats row recycling.
        scrollable = !chapters,
        backIntercept =
            BackIntercept(
                isActive = { chaptersTab?.hasQuery?.invoke() == true },
                clear = { chaptersTab?.clearSearch?.invoke() },
            ),
    ) { frame ->
        val pageStory = frame.state.story ?: story
        if (chapters) {
            val tab = buildChaptersTab(this, frame.model, pageStory, frame.state, route.stableKey, frame.footer)
            chaptersTab = tab
            frame.chaptersTab = tab
        } else {
            frame.aboutAiSlot = addDetailsAbout(this, pageStory, frame.twoPane, frame.state)
        }
    }
}
