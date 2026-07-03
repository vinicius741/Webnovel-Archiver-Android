package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Debug-only "dev launch screen" planning (agent QA convenience).
 *
 * Lets [MainActivity] cold-start directly into a chosen screen via an `am start --es` intent extra,
 * so the agent can test TTS (reader), the download manager (queue), etc. without navigating there by
 * hand. [MainActivity] gates the whole feature behind `BuildConfig.DEBUG`, so this code is dead in
 * the release variant — but the planning lives here as a pure, unit-testable function (no `Intent`
 * dependency) mirroring [com.vinicius741.webnovelarchiver.feature.browser.BrowserImportPlanning] and
 * [com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning].
 *
 * `reader`/`details` need a story (and the reader a chapter). To avoid a disk read on the common
 * no-arg screens, [resolve] only invokes [libraryProvider] for those two targets, auto-picking the
 * first story (and first chapter for the reader) when no explicit override id is supplied. Returns
 * `null` for a missing/unknown token or an empty library, so [MainActivity] falls back to its normal
 * launch flow rather than rendering a blank screen.
 */
object DevLaunchPlanning {
    /** Intent extra carrying the target screen token (e.g. "reader", "queue"). */
    const val EXTRA_DEV_START_SCREEN = "dev_start_screen"

    /** Optional override for the story id used by the `reader`/`details` targets. */
    const val EXTRA_DEV_START_STORY = "dev_start_story"

    /** Optional override for the chapter id used by the `reader` target. */
    const val EXTRA_DEV_START_CHAPTER = "dev_start_chapter"

    /**
     * Screen tokens accepted via [EXTRA_DEV_START_SCREEN]. The [token] is what `am start --es` passes
     * and is matched case-insensitively after trimming.
     */
    enum class DevStartScreen(
        val token: String,
    ) {
        LIBRARY("library"),
        QUEUE("queue"),
        SETTINGS("settings"),
        UPDATES("updates"),
        READER("reader"),
        DETAILS("details"),
        ADD_STORY("addstory"),
        ;

        companion object {
            fun fromToken(token: String?): DevStartScreen? {
                val normalized = token?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
                return values().firstOrNull { it.token == normalized }
            }
        }
    }

    /**
     * The resolved launch target. The no-arg screens are singletons; `reader`/`details` carry the
     * resolved ids so [MainActivity] can hand them straight to `showReader`/`showDetails`.
     */
    sealed interface DevStartTarget {
        data object Library : DevStartTarget

        data object Queue : DevStartTarget

        data object Settings : DevStartTarget

        data object Updates : DevStartTarget

        data object AddStory : DevStartTarget

        data class Reader(
            val storyId: String,
            val chapterId: String,
        ) : DevStartTarget

        data class Details(
            val storyId: String,
        ) : DevStartTarget
    }

    /**
     * Resolves the dev launch target from the intent extras. Returns `null` when the token is
     * missing/unknown or the required story/chapter can't be resolved, so the caller falls through
     * to its normal launch flow.
     *
     * [libraryProvider] is invoked lazily and only for `reader`/`details`, keeping the library disk
     * read off the path for the no-arg screens.
     */
    fun resolve(
        screenName: String?,
        storyOverride: String?,
        chapterOverride: String?,
        libraryProvider: () -> List<Story>,
    ): DevStartTarget? =
        when (DevStartScreen.fromToken(screenName)) {
            null -> null
            DevStartScreen.LIBRARY -> DevStartTarget.Library
            DevStartScreen.QUEUE -> DevStartTarget.Queue
            DevStartScreen.SETTINGS -> DevStartTarget.Settings
            DevStartScreen.UPDATES -> DevStartTarget.Updates
            DevStartScreen.ADD_STORY -> DevStartTarget.AddStory
            DevStartScreen.DETAILS -> resolveDetails(storyOverride, libraryProvider)
            DevStartScreen.READER -> resolveReader(storyOverride, chapterOverride, libraryProvider)
        }

    private fun resolveDetails(
        storyOverride: String?,
        libraryProvider: () -> List<Story>,
    ): DevStartTarget.Details? {
        val story = pickStory(storyOverride, libraryProvider) ?: return null
        return DevStartTarget.Details(story.id)
    }

    private fun resolveReader(
        storyOverride: String?,
        chapterOverride: String?,
        libraryProvider: () -> List<Story>,
    ): DevStartTarget.Reader? {
        val story = pickStory(storyOverride, libraryProvider) ?: return null
        val chapterId = pickChapterId(story, chapterOverride) ?: return null
        return DevStartTarget.Reader(story.id, chapterId)
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
