package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NarrationStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val voice = NarrationVoice("engine", "voice", 1f, "Voice")

    private fun recipe(
        chapter: String = "chapter/1",
        text: String = "Hello there.",
    ) = NarrationRecipe(
        storyId = "story:1",
        chapterId = chapter,
        chunkingVersion = 2,
        textFingerprint = NarrationPlanning.fingerprint(listOf(text), 2),
        sentences = listOf(text),
        voice = voice,
        voiceKey = "key",
    )

    private fun publish(
        store: NarrationStore,
        recipe: NarrationRecipe,
    ): NarrationPack {
        val dir = store.createVersion(recipe)
        store.writeClip(store.clipFile(dir, 0), TtsPcmClip(24_000, ByteArray(480)))
        return store.publish(recipe, dir, NarrationPackIndex(sampleRate = 24_000, sentenceDurationsMs = listOf(10), bytes = 484))
    }

    @Test
    fun publishedPacksSurviveAReload() {
        val root = folder.newFolder()
        val store = NarrationStore(root).apply { load() }
        publish(store, recipe())
        val reloaded = NarrationStore(root).apply { load() }
        val pack = reloaded.pack("story:1", "chapter/1")
        assertNotNull(pack)
        assertEquals(listOf("Hello there."), pack!!.recipe.sentences)
        assertArrayEquals(ByteArray(480), reloaded.readClip(reloaded.clipFile(pack.dir, 0))!!.pcm)
        assertEquals(24_000, reloaded.readClip(reloaded.clipFile(pack.dir, 0))!!.sampleRate)
    }

    @Test
    fun anUnfinishedVersionIsNeverLoadedAsAPack() {
        val root = folder.newFolder()
        val store = NarrationStore(root).apply { load() }
        val dir = store.createVersion(recipe())
        store.writeClip(store.clipFile(dir, 0), TtsPcmClip(24_000, ByteArray(10)))
        assertNull(NarrationStore(root).apply { load() }.pack("story:1", "chapter/1"))
        assertNotNull(store.openVersion("story:1", "chapter/1", dir.name))
    }

    @Test
    fun regeneratingReplacesThePackAndDeletesTheOldVersion() {
        val store = NarrationStore(folder.newFolder()).apply { load() }
        val first = publish(store, recipe())
        val second = publish(store, recipe(text = "Hello again."))
        assertEquals(second.dir, store.pack("story:1", "chapter/1")!!.dir)
        assertFalse(first.dir.exists())
        assertNull(store.openVersion("story:1", "chapter/1", second.version))
    }

    @Test
    fun aVersionPlaybackHoldsIsKeptUntilReleased() {
        val store = NarrationStore(folder.newFolder()).apply { load() }
        val first = publish(store, recipe())
        store.retain(first)
        publish(store, recipe(text = "Hello again."))
        assertTrue(first.dir.exists())
        store.release(first)
        assertFalse(first.dir.exists())
    }

    @Test
    fun sweepKeepsPublishedAndActiveVersionsAndDropsTheRest() {
        val root = folder.newFolder()
        val store = NarrationStore(root).apply { load() }
        val published = publish(store, recipe())
        val active = store.createVersion(recipe(chapter = "chapter/2"))
        val orphan = store.createVersion(recipe(chapter = "chapter/3"))
        File(published.dir, "0001.pcm.tmp").writeText("partial")
        store.sweep(setOf(active.path))
        assertTrue(published.dir.isDirectory)
        assertFalse(File(published.dir, "0001.pcm.tmp").exists())
        assertTrue(active.isDirectory)
        assertFalse(orphan.exists())
    }

    @Test
    fun deletingAStoryRemovesEveryChapter() {
        val store = NarrationStore(folder.newFolder()).apply { load() }
        publish(store, recipe())
        publish(store, recipe(chapter = "chapter/2"))
        store.deleteStory("story:1")
        assertTrue(store.packsFor("story:1").isEmpty())
        assertFalse(store.chapterDir("story:1", "chapter/1").exists())
    }
}
