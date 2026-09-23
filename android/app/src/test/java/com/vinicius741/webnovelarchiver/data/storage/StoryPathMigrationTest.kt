package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class StoryPathMigrationTest {
    private val root = File("/data/user/0/app/files/webnovel_archiver")

    @Test
    fun currentPathsKeepOriginalStoryAndChapterList() {
        val story =
            Story(
                chapters = mutableListOf(Chapter(filePath = "novels/story/one.html")),
                epubPath = "epubs/story/book.epub",
                epubPaths = mutableListOf("epubs/story/book.epub"),
            )

        val migrated = migrateStoryPaths(story, root)

        assertSame(story, migrated)
        assertSame(story.chapters, migrated.chapters)
    }

    @Test
    fun convertsOnlyPathsUnderLibraryRoot() {
        val story =
            Story(
                chapters =
                    mutableListOf(
                        Chapter(filePath = "${root.path}/novels/story/one.html"),
                        Chapter(filePath = "/other/install/chapter.html"),
                        Chapter(filePath = "novels/story/three.html"),
                    ),
                epubPath = "${root.path}/epubs/story/book.epub",
                epubPaths = mutableListOf("/other/install/book.epub", "${root.path}/epubs/story/book.epub"),
            )

        val migrated = migrateStoryPaths(story, root)

        assertEquals("novels/story/one.html", migrated.chapters[0].filePath)
        assertEquals("/other/install/chapter.html", migrated.chapters[1].filePath)
        assertEquals("novels/story/three.html", migrated.chapters[2].filePath)
        assertEquals("epubs/story/book.epub", migrated.epubPath)
        assertEquals(listOf("/other/install/book.epub", "epubs/story/book.epub"), migrated.epubPaths)
        assertEquals("${root.path}/novels/story/one.html", story.chapters[0].filePath)
    }
}
