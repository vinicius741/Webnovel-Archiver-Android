package com.vinicius741.webnovelarchiver.domain.story

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryNormalizationTest {
    private val gson = Gson()

    @Test
    fun coerceDefaultsFillsNullPublicationStatusFromLegacyJson() {
        // Simulate R8-stripped no-arg ctor: Unsafe leaves missing fields null, including
        // publicationStatus which was added after early library installs.
        val story =
            gson.fromJson(
                """{"id":"abc","title":"Legacy","author":"A","sourceUrl":"https://x","chapters":[]}""",
                Story::class.java,
            )
        // Force the release-path failure mode even when the JVM no-arg ctor applied defaults.
        nullField(story, "publicationStatus")

        val result = StoryNormalization.coerceDefaults(story)
        assertTrue(result.changed)
        assertEquals(PublicationStatus.unknown, result.story.publicationStatus)
        // copy must not throw after coercion (this is what crashed phone startup).
        val snap = result.story.copy(title = result.story.title)
        assertEquals("Legacy", snap.title)
        assertEquals(PublicationStatus.unknown, snap.publicationStatus)
    }

    @Test
    fun coerceDefaultsIsNoOpWhenFieldsAlreadyPresent() {
        val story =
            Story(
                id = "ok",
                title = "Ok",
                status = DownloadStatus.completed,
                publicationStatus = PublicationStatus.ongoing,
            )
        val result = StoryNormalization.coerceDefaults(story)
        assertFalse(result.changed)
        assertEquals(PublicationStatus.ongoing, result.story.publicationStatus)
        assertEquals(DownloadStatus.completed, result.story.status)
    }

    @Test
    fun coerceDefaultsFillsNullChaptersList() {
        val story = Story(id = "c", title = "C")
        nullField(story, "chapters")
        val result = StoryNormalization.coerceDefaults(story)
        assertTrue(result.changed)
        assertNotNull(result.story.chapters)
        assertTrue(result.story.chapters.isEmpty())
        // snapshot-style copy used by AppRepository.refresh must succeed.
        result.story.copy(
            chapters =
                result.story.chapters
                    .map { it.copy() }
                    .toMutableList(),
        )
    }

    @Test
    fun coerceDefaultsFillsNullChapterScalars() {
        val chapter = Chapter(id = "ch1", title = "One", url = "https://x/1")
        nullField(chapter, "title")
        val story = Story(id = "s", title = "S", chapters = mutableListOf(chapter))
        val result = StoryNormalization.coerceDefaults(story)
        assertTrue(result.changed)
        assertEquals(
            "",
            result.story.chapters
                .single()
                .title,
        )
    }

    /** Mirror Gson/Unsafe leaving a Kotlin non-null field null at runtime. */
    private fun nullField(
        target: Any,
        fieldName: String,
    ) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, null)
    }
}
