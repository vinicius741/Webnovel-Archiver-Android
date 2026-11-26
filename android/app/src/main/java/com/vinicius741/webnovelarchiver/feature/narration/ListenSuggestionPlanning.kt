package com.vinicius741.webnovelarchiver.feature.narration

/** One story's generated audio as the suggestions see it. */
internal data class StoryAudioFacts(
    val storyId: String,
    val title: String,
    /** The story's chapter ids in reading order. */
    val chapterIds: List<String>,
    /** -1 without a bookmark. */
    val bookmarkIndex: Int,
    /** Generated audio size by chapter id. */
    val bytes: Map<String, Long>,
    /** When listening last stopped in this story, if ever. */
    val lastListenedAt: Long?,
)

/** A clean-up the Listen screen offers: the chapters to delete, by story, and what it frees. */
internal data class ListenSuggestion(
    val kind: Kind,
    val titles: List<String>,
    val chapters: Map<String, List<String>>,
    val bytes: Long,
    val idleDays: Int = 0,
) {
    enum class Kind { FINISHED, ON_BREAK, HEARD }

    val chapterCount: Int get() = chapters.values.sumOf { it.size }
}

/**
 * Clean-ups worked out from each story's bookmark and last listen: a finished story (bookmark on
 * its last chapter) loses all its audio; a story not listened to for [IDLE_DAYS] keeps only the
 * [KEEP_ON_BREAK] chapters from its bookmark; every other story's audio before its bookmark is
 * offered together as already heard. A suggestion that would free nothing is left out.
 */
internal object ListenSuggestionPlanning {
    const val IDLE_DAYS = 14
    const val KEEP_ON_BREAK = 10
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun suggestions(
        stories: List<StoryAudioFacts>,
        now: Long,
    ): List<ListenSuggestion> {
        val result = mutableListOf<ListenSuggestion>()
        val heard = mutableListOf<Pair<StoryAudioFacts, List<String>>>()
        stories.filter { it.bytes.isNotEmpty() }.forEach { story ->
            val withAudio = story.chapterIds.filter { it in story.bytes }
            val idleDays = story.lastListenedAt?.let { ((now - it) / DAY_MS).toInt() } ?: 0
            when {
                story.chapterIds.isNotEmpty() && story.bookmarkIndex == story.chapterIds.lastIndex ->
                    result += suggestion(ListenSuggestion.Kind.FINISHED, story, withAudio)
                idleDays >= IDLE_DAYS -> {
                    val start = story.bookmarkIndex.coerceAtLeast(0)
                    val kept = story.chapterIds.subList(start, (start + KEEP_ON_BREAK).coerceAtMost(story.chapterIds.size)).toSet()
                    val delete = withAudio.filter { it !in kept }
                    if (delete.isNotEmpty()) result += suggestion(ListenSuggestion.Kind.ON_BREAK, story, delete, idleDays)
                }
                story.bookmarkIndex > 0 -> {
                    val before = story.chapterIds.take(story.bookmarkIndex).filter { it in story.bytes }
                    if (before.isNotEmpty()) heard += story to before
                }
            }
        }
        if (heard.isNotEmpty()) {
            result +=
                ListenSuggestion(
                    ListenSuggestion.Kind.HEARD,
                    heard.map { it.first.title },
                    heard.associate { (story, ids) -> story.storyId to ids },
                    heard.sumOf { (story, ids) -> ids.sumOf { story.bytes[it] ?: 0L } },
                )
        }
        return result
    }

    private fun suggestion(
        kind: ListenSuggestion.Kind,
        story: StoryAudioFacts,
        ids: List<String>,
        idleDays: Int = 0,
    ) = ListenSuggestion(kind, listOf(story.title), mapOf(story.storyId to ids), ids.sumOf { story.bytes[it] ?: 0L }, idleDays)
}
