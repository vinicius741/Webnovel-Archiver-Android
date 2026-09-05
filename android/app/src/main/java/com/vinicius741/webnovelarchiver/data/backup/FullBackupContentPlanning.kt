package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.File

/**
 * R10: decides, before the ZIP is written, which downloaded chapters have exportable content and
 * which expected items are missing. A successful export must never silently omit content the
 * library reports as available.
 */
object FullBackupContentPlanning {
    /** One expected-but-absent item, recorded in the manifest's `missingContent` list. */
    data class MissingContent(
        val kind: String,
        val storyId: String,
        val chapterId: String?,
        val title: String?,
    )

    /** The export decision for one downloaded chapter. */
    sealed interface ChapterContent {
        val storyId: String
        val chapterId: String

        /** A chapter file exists on disk; stream it. */
        data class FromFile(
            override val storyId: String,
            override val chapterId: String,
            val chapterIndex: Int,
            val title: String,
            val path: String,
            val file: File,
        ) : ChapterContent

        /** No file, but legacy inline [Chapter.content] survives; materialize those bytes. */
        data class Inline(
            override val storyId: String,
            override val chapterId: String,
            val chapterIndex: Int,
            val title: String,
            val path: String,
            val content: String,
        ) : ChapterContent

        /** Neither file nor inline content: expected, missing, reported. */
        data class Missing(
            override val storyId: String,
            override val chapterId: String,
            val title: String,
        ) : ChapterContent
    }

    /**
     * Classifies every downloaded chapter of [library]. [resolveFile] maps the stored (possibly
     * relative) chapter path to an existing file or null; [chapterPath] builds the zip path.
     */
    fun planChapterContent(
        library: List<Story>,
        resolveFile: (Chapter) -> File?,
        chapterPath: (storyId: String, chapterId: String, index: Int) -> String,
    ): List<ChapterContent> =
        library.flatMap { story ->
            story.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.downloaded) return@mapIndexedNotNull null
                when (val file = resolveFile(chapter)) {
                    is File ->
                        ChapterContent.FromFile(
                            storyId = story.id,
                            chapterId = chapter.id,
                            chapterIndex = index,
                            title = chapter.title,
                            path = chapterPath(story.id, chapter.id, index),
                            file = file,
                        )
                    null ->
                        chapter.content?.let { inline ->
                            ChapterContent.Inline(
                                storyId = story.id,
                                chapterId = chapter.id,
                                chapterIndex = index,
                                title = chapter.title,
                                path = chapterPath(story.id, chapter.id, index),
                                content = inline,
                            )
                        } ?: ChapterContent.Missing(story.id, chapter.id, chapter.title)
                }
            }
        }

    /** Manifest-ready missing list: missing chapters plus filtered applied-rewrite files. */
    fun missingContentReport(
        chapterPlan: List<ChapterContent>,
        missingAppliedByStory: Map<String, Int>,
    ): List<MissingContent> =
        chapterPlan.mapNotNull { entry ->
            if (entry is ChapterContent.Missing) {
                MissingContent(kind = "chapter", storyId = entry.storyId, chapterId = entry.chapterId, title = entry.title)
            } else {
                null
            }
        } +
            missingAppliedByStory.flatMap { (storyId, count) ->
                (0 until count).map { MissingContent(kind = "applied_rewrite", storyId = storyId, chapterId = null, title = null) }
            }
}
