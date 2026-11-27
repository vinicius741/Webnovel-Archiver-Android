package com.vinicius741.webnovelarchiver.profiling

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * A small, deterministic Royal Road-shaped library: enough novels for the Library to scroll, each
 * with downloaded chapters (real chapter files, like a download writes) for the Chapters tab and the
 * Reader. Titles are stable because the generator finds them on screen ("Fixture Novel 01").
 */
internal object ProfilingFixture {
    const val STORY_COUNT = 24
    private const val CHAPTER_COUNT = 80
    private const val DOWNLOADED_CHAPTERS = 60
    private const val PARAGRAPHS = 36
    private const val WORDS_PER_PARAGRAPH = 70
    private const val FIRST_FICTION_ID = 9_000_000
    private const val DAY_MILLIS = 86_400_000L
    private const val BASE_TIME = 1_760_000_000_000L
    private val words =
        (
            "the archive of quiet stars held every story she had ever read and a few she had only dreamed " +
                "about while the lanterns burned low over the river city and the wind carried rumours of a " +
                "new road through the northern mountains where the old guild kept its ledgers"
        ).split(' ')

    fun title(number: Int): String = "Fixture Novel %02d".format(number)

    /** Adds the fixture novels that are missing and returns how many it added. */
    suspend fun seed(repository: AppRepository): Int {
        val existing = repository.library().mapTo(HashSet()) { it.id }
        var added = 0
        for (number in 1..STORY_COUNT) {
            val fictionId = FIRST_FICTION_ID + number
            val storyId = "rr_$fictionId"
            if (storyId in existing) continue
            repository.upsertStory(story(repository, number, fictionId, storyId))
            added++
        }
        return added
    }

    private fun story(
        repository: AppRepository,
        number: Int,
        fictionId: Int,
        storyId: String,
    ): Story {
        val storyUrl = "https://www.royalroad.com/fiction/$fictionId/fixture-novel-$number"
        val updated = BASE_TIME + number * DAY_MILLIS
        val chapters =
            (1..CHAPTER_COUNT).map { index ->
                val chapterId = (fictionId.toLong() * 1_000 + index).toString()
                Chapter(
                    id = chapterId,
                    title = "Chapter $index: ${words[(index * 7) % words.size].replaceFirstChar(Char::titlecase)} road",
                    url = "$storyUrl/chapter/$chapterId/chapter-$index",
                    publishedAt = updated - (CHAPTER_COUNT - index) * DAY_MILLIS,
                )
            }
        chapters.take(DOWNLOADED_CHAPTERS).forEachIndexed { index, chapter ->
            chapter.filePath = repository.storage.saveChapter(storyId, index, chapter, chapterHtml(number, index))
            chapter.downloaded = true
            chapter.downloadedAt = updated
        }
        return Story(
            id = storyId,
            title = title(number),
            author = "Fixture Author ${(number % 5) + 1}",
            description = paragraph(number, 0).replaceFirstChar(Char::titlecase),
            sourceUrl = storyUrl,
            sourceId = "royal_road",
            status = DownloadStatus.partial,
            chapters = chapters.toMutableList(),
            lastUpdated = updated,
            dateAdded = updated,
            tags = mutableListOf("Fantasy", "Adventure", if (number % 2 == 0) "LitRPG" else "Slice of Life"),
            score = "4.${number % 10}",
            lastReadChapterId = chapters[number % DOWNLOADED_CHAPTERS].id,
            publicationStatus = PublicationStatus.ongoing,
        )
    }

    private fun chapterHtml(
        story: Int,
        chapter: Int,
    ): String =
        buildString {
            append("<div class=\"chapter-content\">")
            repeat(PARAGRAPHS) { paragraph ->
                append("<p>").append(paragraph(story * 31 + chapter, paragraph).replaceFirstChar(Char::titlecase)).append(".</p>")
            }
            append("</div>")
        }

    private fun paragraph(
        seed: Int,
        index: Int,
    ): String = (0 until WORDS_PER_PARAGRAPH).joinToString(" ") { word -> words[(seed * 13 + index * 7 + word * 3) % words.size] }
}
