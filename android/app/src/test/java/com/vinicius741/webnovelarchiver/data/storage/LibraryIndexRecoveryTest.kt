package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LibraryIndexRecoveryTest {
    private lateinit var dir: File
    private val gson = Gson()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "library_recovery_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun scanRecoversOnlyValidCanonicalStoryFilesWithoutWriting() {
        val valid = storyFile("story-1", Story(id = "story-1", title = "One"))
        val wrongName = storyFile("alias", Story(id = "story-2", title = "Two"))
        val malformed = File(dir, "broken.json").apply { writeText("{not-json") }
        val before = dir.listFiles().orEmpty().associate { it.name to it.readText() }

        val result =
            LibraryIndexRecovery.scan(
                files = dir.listFiles().orEmpty().toList(),
                safeName = { it },
                readStory = { file -> DurableJson.decodeText(file.readText(), gson) },
            )

        assertEquals(listOf("story-1"), result.stories.map { it.id })
        assertEquals(setOf(wrongName, malformed), result.rejectedFiles.toSet())
        assertTrue(valid.exists())
        assertEquals(before, dir.listFiles().orEmpty().associate { it.name to it.readText() })
    }

    private fun storyFile(
        filename: String,
        story: Story,
    ): File = File(dir, "$filename.json").apply { writeText(gson.toJson(DurableJson.envelope(story, "test"))) }
}
