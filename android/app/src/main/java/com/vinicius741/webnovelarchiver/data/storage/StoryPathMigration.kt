package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.File

/** Converts legacy absolute paths only when needed; current relative paths keep their lists. */
internal fun migrateStoryPaths(
    story: Story,
    root: File,
): Story {
    fun convert(path: String): String? {
        if (!path.startsWith('/')) return null
        val file = File(path)
        return if (file.isAbsolute && file.startsWith(root)) file.toRelativeString(root) else null
    }

    var chapters: MutableList<Chapter>? = null
    story.chapters.forEachIndexed { index, chapter ->
        val relative = chapter.filePath?.let(::convert) ?: return@forEachIndexed
        val updated = chapters ?: story.chapters.toMutableList().also { chapters = it }
        updated[index] = chapter.copy(filePath = relative)
    }

    var epubPaths: MutableList<String>? = null
    story.epubPaths?.forEachIndexed { index, path ->
        val relative = convert(path) ?: return@forEachIndexed
        val updated =
            epubPaths ?: story.epubPaths
                .orEmpty()
                .toMutableList()
                .also { epubPaths = it }
        updated[index] = relative
    }
    val epubPath = story.epubPath?.let(::convert)
    if (chapters == null && epubPaths == null && epubPath == null) return story
    return story.copy(
        chapters = chapters ?: story.chapters,
        epubPaths = epubPaths ?: story.epubPaths,
        epubPath = epubPath ?: story.epubPath,
    )
}
