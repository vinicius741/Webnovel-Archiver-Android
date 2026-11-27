package com.vinicius741.webnovelarchiver.feature.narration

/** One story's generated audio as the suggestions see it. */
internal data class StoryAudioFacts(
    val storyId: String,
    val title: String,
    /** The story's chapter ids in reading order. */
    val chapterIds: List<String>,
    /** -1 without a bookmark. */
    val bookmarkIndex: Int,
    /** Where listening stopped; -1 if never. */
    val listenIndex: Int = -1,
    /** Generated audio size by chapter id. */
    val bytes: Map<String, Long>,
    /** When listening last stopped in this story, if ever. */
    val lastListenedAt: Long?,
    /** Chapters waiting in the generation queue: they go with the audio, or the queue would make it again. */
    val queued: Set<String> = emptySet(),
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
 * Clean-ups worked out from each story's bookmark and last listen. Each story's place is the earlier
 * of its bookmark and its listening place (whichever exists if only one does), so audio not yet
 * heard is never offered. A finished story (place on its last chapter) loses all its audio; a story
 * not listened to for [IDLE_DAYS] keeps only the [KEEP_ON_BREAK] chapters from its place; every
 * other story's audio before its place is offered together as already heard. Queued chapters in
 * those ranges go too. A suggestion that would free nothing is left out.
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
            val withAudio = story.chapterIds.filter { it in story.bytes || it in story.queued }
            val idleDays = story.lastListenedAt?.let { ((now - it) / DAY_MS).toInt() } ?: 0
            val place = placeOf(story)
            when {
                place < 0 || withAudio.isEmpty() -> Unit
                place == story.chapterIds.lastIndex -> result += suggestion(ListenSuggestion.Kind.FINISHED, story, withAudio)
                idleDays >= IDLE_DAYS -> {
                    val kept = story.chapterIds.subList(place, (place + KEEP_ON_BREAK).coerceAtMost(story.chapterIds.size)).toSet()
                    val delete = withAudio.filter { it !in kept }
                    if (delete.any { it in story.bytes }) result += suggestion(ListenSuggestion.Kind.ON_BREAK, story, delete, idleDays)
                }
                place > 0 -> {
                    val before = story.chapterIds.take(place).filter { it in story.bytes || it in story.queued }
                    if (before.any { it in story.bytes }) heard += story to before
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

    private fun placeOf(story: StoryAudioFacts): Int =
        when {
            story.bookmarkIndex >= 0 && story.listenIndex >= 0 -> minOf(story.bookmarkIndex, story.listenIndex)
            story.bookmarkIndex >= 0 -> story.bookmarkIndex
            else -> story.listenIndex
        }

    private fun suggestion(
        kind: ListenSuggestion.Kind,
        story: StoryAudioFacts,
        ids: List<String>,
        idleDays: Int = 0,
    ) = ListenSuggestion(kind, listOf(story.title), mapOf(story.storyId to ids), ids.sumOf { story.bytes[it] ?: 0L }, idleDays)
}
