package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.AppRoute

/**
 * Debug-only launch planning for QA: cold-start into a chosen screen via `am start --es` extras,
 * gated behind BuildConfig.DEBUG in MainActivity. [resolve] invokes [libraryProvider] only for
 * story-requiring targets (keeps the disk read off no-arg screens) and returns null when the
 * token is unknown or the story/chapter can't be resolved, so the caller falls back to a normal
 * launch.
 */
object DevLaunchPlanning {
    const val EXTRA_DEV_START_SCREEN = "dev_start_screen"

    const val EXTRA_DEV_START_STORY = "dev_start_story"

    const val EXTRA_DEV_START_CHAPTER = "dev_start_chapter"

    enum class DevStartScreen(
        val token: String,
    ) {
        LIBRARY("library"),
        QUEUE("queue"),
        SETTINGS("settings"),
        NOTIFICATIONS("notifications"),
        UPDATES("updates"),
        READER("reader"),
        DETAILS("details"),
        AI_CONTROLS("aicontrols"),
        ADD_STORY("addstory"),
        FOLLOW_UPDATES("followupdates"),
        AI_SETTINGS("aisettings"),
        ;

        companion object {
            fun fromToken(token: String?): DevStartScreen? {
                val normalized = token?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
                return values().firstOrNull { it.token == normalized }
            }
        }
    }

    fun resolve(
        screenName: String?,
        storyOverride: String?,
        chapterOverride: String?,
        libraryProvider: () -> List<Story>,
    ): AppRoute? =
        when (DevStartScreen.fromToken(screenName)) {
            null -> null
            DevStartScreen.LIBRARY -> AppRoute.Library
            DevStartScreen.QUEUE -> AppRoute.Queue
            DevStartScreen.SETTINGS -> AppRoute.Settings
            DevStartScreen.AI_SETTINGS -> AppRoute.AiSettings
            DevStartScreen.NOTIFICATIONS -> AppRoute.Notifications
            DevStartScreen.UPDATES -> AppRoute.Updates
            DevStartScreen.ADD_STORY -> AppRoute.AddStory
            DevStartScreen.FOLLOW_UPDATES -> AppRoute.UpdateFollowSelection
            DevStartScreen.DETAILS -> resolveDetails(storyOverride, libraryProvider)
            DevStartScreen.AI_CONTROLS -> resolveAiControls(storyOverride, libraryProvider)
            DevStartScreen.READER -> resolveReader(storyOverride, chapterOverride, libraryProvider)
        }

    private fun resolveDetails(
        storyOverride: String?,
        libraryProvider: () -> List<Story>,
    ): AppRoute.Details? {
        val story = pickStory(storyOverride, libraryProvider) ?: return null
        return AppRoute.Details(story.id)
    }

    private fun resolveAiControls(
        storyOverride: String?,
        libraryProvider: () -> List<Story>,
    ): AppRoute.AiControls? {
        val story = pickStory(storyOverride, libraryProvider) ?: return null
        return AppRoute.AiControls(story.id)
    }

    private fun resolveReader(
        storyOverride: String?,
        chapterOverride: String?,
        libraryProvider: () -> List<Story>,
    ): AppRoute.Reader? {
        val story = pickStory(storyOverride, libraryProvider) ?: return null
        val chapterId = pickChapterId(story, chapterOverride) ?: return null
        return AppRoute.Reader(story.id, chapterId)
    }

    private fun pickStory(
        storyOverride: String?,
        libraryProvider: () -> List<Story>,
    ): Story? {
        val library = libraryProvider()
        val overridden = storyOverride?.trim()?.takeIf { it.isNotEmpty() }
        return if (overridden != null) {
            library.firstOrNull { it.id == overridden }
        } else {
            library.firstOrNull()
        }
    }

    private fun pickChapterId(
        story: Story,
        chapterOverride: String?,
    ): String? {
        val overridden = chapterOverride?.trim()?.takeIf { it.isNotEmpty() }
        return if (overridden != null) {
            story.chapters.firstOrNull { it.id == overridden }?.id
        } else {
            story.chapters.firstOrNull()?.id
        }
    }
}
