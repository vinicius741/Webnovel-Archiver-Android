package com.vinicius741.webnovelarchiver.navigation

/**
 * A stable, display-text-independent identity for every full-screen destination.
 *
 * Route arguments deliberately contain persisted ids rather than mutable titles. [stableKey] is
 * therefore safe to use for view state and process-recreation restoration even when story metadata
 * changes while a screen is open.
 */
sealed class AppRoute(
    val name: String,
) {
    data object Library : AppRoute("library")

    data object AddStory : AppRoute("add_story")

    /**
     * The story page. Its tabs are routes of their own so Back and deep links address them: this
     * route is the Overview tab, or the Chapters tab (with each chapter's audio) when [chapters] is
     * set; [Trends] is the Trends tab and [AiControls] the AI tab. Tab switches replace the
     * current history entry, so Back leaves the story instead of walking its tabs.
     */
    data class Details(
        val storyId: String,
        val chapters: Boolean = false,
    ) : AppRoute("details")

    data class ChapterSelection(
        val storyId: String,
        val selectedChapterIds: Set<String> = emptySet(),
    ) : AppRoute("chapter_selection")

    /**
     * Per-novel AI Controls sub-screen: the hub for AI-generated content (description today). Reached
     * from the Details screen's "More options" menu so the Details body stays free of per-feature AI
     * buttons as more generators are added.
     */
    data class AiControls(
        val storyId: String,
    ) : AppRoute("ai_controls")

    /**
     * Per-novel metric Trends sub-screen. [focus] optionally opens the screen scrolled/emphasized to
     * a particular series (`"score"`, `"patreon_members"`, `"patreon_usd"`); `null` opens the generic
     * view (used by the overflow-menu entry).
     */
    data class Trends(
        val storyId: String,
        val focus: String? = null,
    ) : AppRoute("trends")

    /** Other library novels by the same author as [storyId]; reached by tapping the author on Details. */
    data class AuthorNovels(
        val storyId: String,
    ) : AppRoute("author_novels")

    data class Reader(
        val storyId: String,
        val chapterId: String,
    ) : AppRoute("reader")

    data object Queue : AppRoute("queue")

    /**
     * The Updates destination: every novel's release forecast (may be out, expected soon, running
     * late, recently synced) with per-novel and "sync all that may be out" actions.
     */
    data object Updates : AppRoute("updates")

    /** Novels whose statistics are rising now, reached from Updates' app bar. */
    data object Trending : AppRoute("trending")

    /** Podcast-style Now Playing screen (TTS transport, progress, speed). */
    data object Player : AppRoute("player")

    /** The Listen destination: the player card, the generation queue and every story's audio. */
    data object Narration : AppRoute("narration")

    data object Settings : AppRoute("settings")

    data object DownloadSettings : AppRoute("download_settings")

    data object TtsSettings : AppRoute("tts_settings")

    data object AiSettings : AppRoute("ai_settings")

    data object Tabs : AppRoute("tabs")

    data object CleanupRules : AppRoute("cleanup_rules")

    data object DataBackup : AppRoute("data_backup")

    /** Installed version, update check, and the in-app download/install of a newer release. */
    data object AppUpdate : AppRoute("app_update")

    /**
     * Patreon sign-in guide and account status. [storyId] is the novel whose Patreon card opened
     * it, so Back returns there and a fresh sign-in can check that novel; `null` from Settings.
     */
    data class PatreonAccount(
        val storyId: String? = null,
    ) : AppRoute("patreon_account")

    /** An operation progress surface is intentionally not restored after process death. */
    data object Working : AppRoute("working")

    val stableKey: String
        get() =
            when (this) {
                is Details -> "$name:${AppRouteCodec.encodeArgument(storyId)}${if (chapters) ":$DETAILS_CHAPTERS_TAB" else ""}"
                is Reader -> "$name:${AppRouteCodec.encodeArgument(storyId)}:${AppRouteCodec.encodeArgument(chapterId)}"
                is AiControls -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                is AuthorNovels -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                is ChapterSelection -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                is Trends -> "$name:${AppRouteCodec.encodeArgument(storyId)}:${focus ?: ""}"
                is PatreonAccount -> "$name:${storyId?.let(AppRouteCodec::encodeArgument).orEmpty()}"
                else -> name
            }

    /**
     * The story whose page this route is a tab of (Overview, Chapters, Audio, Trends, AI), else null.
     * All tabs of one story count as a single history entry.
     */
    val storyTabOf: String?
        get() =
            when (this) {
                is Details -> storyId
                is Trends -> storyId
                is AiControls -> storyId
                else -> null
            }

    /**
     * The screen Back lands on when nothing in the history precedes this one (the app opened
     * straight into it). `null` only for the [Library] root.
     */
    val parent: AppRoute?
        get() =
            when (this) {
                Library -> null
                is Reader -> Details(storyId)
                is ChapterSelection -> Details(storyId)
                is AuthorNovels -> Details(storyId)
                is PatreonAccount -> storyId?.let(::Details) ?: Settings
                Trending -> Updates
                DownloadSettings, TtsSettings, AiSettings, Tabs, CleanupRules, DataBackup, AppUpdate -> Settings
                else -> Library
            }
}

/** The [AppRoute.Details] argument that selects the Chapters tab. */
private const val DETAILS_CHAPTERS_TAB = "chapters"

/** Every tab route of one story page, for view-memory retention across tab switches. */
internal fun storyTabRoutes(storyId: String): List<AppRoute> =
    listOf(
        AppRoute.Details(storyId),
        AppRoute.Details(storyId, chapters = true),
        AppRoute.Trends(storyId),
        AppRoute.AiControls(storyId),
    )

/** Pure, version-tolerant route encoding used by Android saved state and unit tests. */
object AppRouteCodec {
    internal fun encodeArgument(value: String): String = hexEncode(value)

    fun encode(route: AppRoute): String {
        val arguments =
            when (route) {
                is AppRoute.Details -> listOfNotNull(route.storyId, DETAILS_CHAPTERS_TAB.takeIf { route.chapters })
                is AppRoute.Reader -> listOf(route.storyId, route.chapterId)
                is AppRoute.AiControls -> listOf(route.storyId)
                is AppRoute.AuthorNovels -> listOf(route.storyId)
                is AppRoute.Trends -> listOf(route.storyId) + listOfNotNull(route.focus?.takeIf { it.isNotBlank() })
                is AppRoute.ChapterSelection -> listOf(route.storyId) + route.selectedChapterIds.sorted()
                is AppRoute.PatreonAccount -> listOfNotNull(route.storyId)
                else -> emptyList()
            }
        return (listOf(route.name) + arguments.map(::hexEncode)).joinToString(":")
    }

    fun decode(value: String): AppRoute? {
        val parts = value.split(':')
        val arguments = parts.drop(1).map { hexDecode(it) ?: return null }
        return when (parts.firstOrNull()) {
            "library" -> AppRoute.Library
            "add_story" -> AppRoute.AddStory
            "queue" -> AppRoute.Queue
            "updates" -> AppRoute.Updates
            "trending" -> AppRoute.Trending
            "player" -> AppRoute.Player
            "narration" -> AppRoute.Narration
            "settings" -> AppRoute.Settings
            // The Notifications page was replaced by one Settings row; a saved route lands there.
            "notifications" -> AppRoute.Settings
            "download_settings" -> AppRoute.DownloadSettings
            "tts_settings" -> AppRoute.TtsSettings
            "ai_settings" -> AppRoute.AiSettings
            "tabs" -> AppRoute.Tabs
            "cleanup_rules" -> AppRoute.CleanupRules
            "data_backup" -> AppRoute.DataBackup
            "app_update" -> AppRoute.AppUpdate
            "working" -> AppRoute.Working
            // Argument-bearing routes resolve in a helper so this `when` stays under detekt's
            // cyclomatic-complexity budget as the route table grows.
            else -> decodeRouteWithArguments(parts.firstOrNull(), arguments)
        }
    }

    private fun decodeRouteWithArguments(
        name: String?,
        arguments: List<String>,
    ): AppRoute? =
        when (name) {
            "details" ->
                when {
                    arguments.size == 1 -> AppRoute.Details(arguments[0])
                    arguments.size == 2 && arguments[1] == DETAILS_CHAPTERS_TAB -> AppRoute.Details(arguments[0], chapters = true)
                    else -> null
                }
            "chapter_selection" -> arguments.firstOrNull()?.let { AppRoute.ChapterSelection(it, arguments.drop(1).toSet()) }
            // The EPUB files screen was removed (leftovers are deleted automatically): its story instead.
            "legacy_epubs" -> arguments.singleOrNull()?.let { AppRoute.Details(it) }
            "ai_controls" -> arguments.singleOrNull()?.let(AppRoute::AiControls)
            "author_novels" -> arguments.singleOrNull()?.let(AppRoute::AuthorNovels)
            "trends" -> arguments.firstOrNull()?.let { AppRoute.Trends(it, arguments.getOrNull(1)) }
            "patreon_account" -> if (arguments.size <= 1) AppRoute.PatreonAccount(arguments.firstOrNull()) else null
            "reader" -> if (arguments.size == 2) AppRoute.Reader(arguments[0], arguments[1]) else null
            // The Audio tab merged into the Chapters tab.
            "narration_story" -> if (arguments.size in 1..2) AppRoute.Details(arguments[0], chapters = true) else null
            else -> null
        }

    private fun hexEncode(value: String): String =
        value.encodeToByteArray().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hexDecode(value: String): String? {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return null
        return runCatching {
            ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }.decodeToString()
        }.getOrNull()
    }
}
