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

    data class LibrarySelection(
        val selectedStoryIds: Set<String> = emptySet(),
    ) : AppRoute("library_selection")

    data class Details(
        val storyId: String,
    ) : AppRoute("details")

    data class ChapterSelection(
        val storyId: String,
        val selectedChapterIds: Set<String> = emptySet(),
    ) : AppRoute("chapter_selection")

    data class LegacyEpubs(
        val storyId: String,
    ) : AppRoute("legacy_epubs")

    data class Reader(
        val storyId: String,
        val chapterId: String,
    ) : AppRoute("reader")

    data object Queue : AppRoute("queue")

    data object Updates : AppRoute("updates")

    data object UpdateFollowSelection : AppRoute("update_follow_selection")

    data object Settings : AppRoute("settings")

    data object DownloadSettings : AppRoute("download_settings")

    data object TtsSettings : AppRoute("tts_settings")

    data object Tabs : AppRoute("tabs")

    data object CleanupRules : AppRoute("cleanup_rules")

    /** An operation progress surface is intentionally not restored after process death. */
    data object Working : AppRoute("working")

    val stableKey: String
        get() =
            when (this) {
                is Details -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                is Reader -> "$name:${AppRouteCodec.encodeArgument(storyId)}:${AppRouteCodec.encodeArgument(chapterId)}"
                is LegacyEpubs -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                is ChapterSelection -> "$name:${AppRouteCodec.encodeArgument(storyId)}"
                else -> name
            }
}

/** Pure, version-tolerant route encoding used by Android saved state and unit tests. */
object AppRouteCodec {
    internal fun encodeArgument(value: String): String = hexEncode(value)

    fun encode(route: AppRoute): String {
        val arguments =
            when (route) {
                is AppRoute.Details -> listOf(route.storyId)
                is AppRoute.Reader -> listOf(route.storyId, route.chapterId)
                is AppRoute.LegacyEpubs -> listOf(route.storyId)
                is AppRoute.LibrarySelection -> route.selectedStoryIds.sorted()
                is AppRoute.ChapterSelection -> listOf(route.storyId) + route.selectedChapterIds.sorted()
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
            "library_selection" -> AppRoute.LibrarySelection(arguments.toSet())
            "details" -> arguments.singleOrNull()?.let(AppRoute::Details)
            "chapter_selection" -> arguments.firstOrNull()?.let { AppRoute.ChapterSelection(it, arguments.drop(1).toSet()) }
            "legacy_epubs" -> arguments.singleOrNull()?.let(AppRoute::LegacyEpubs)
            "reader" -> if (arguments.size == 2) AppRoute.Reader(arguments[0], arguments[1]) else null
            "queue" -> AppRoute.Queue
            "updates" -> AppRoute.Updates
            "update_follow_selection" -> AppRoute.UpdateFollowSelection
            "settings" -> AppRoute.Settings
            "download_settings" -> AppRoute.DownloadSettings
            "tts_settings" -> AppRoute.TtsSettings
            "tabs" -> AppRoute.Tabs
            "cleanup_rules" -> AppRoute.CleanupRules
            "working" -> AppRoute.Working
            else -> null
        }
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
