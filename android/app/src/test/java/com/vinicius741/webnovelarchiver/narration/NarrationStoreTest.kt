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

    private fun pcm(frames: Int) = ByteArray(frames * 2) { (it * 7).toByte() }

    /** A pack as releases before compression wrote it: `NNNN.pcm` legacy clips. */
    private fun publishLegacy(
        store: NarrationStore,
        recipe: NarrationRecipe,
        frames: Int = 240,
    ): NarrationPack {
        val dir = store.createVersion(recipe)
        recipe.sentences.indices.forEach { File(dir, "%04d.pcm".format(it)).writeBytes(NarrationClipFormat.legacy(24_000, pcm(frames))) }
        val index = store.indexFor(dir, recipe, generatedAt = 7L)!!
        assertEquals(NarrationStore.CODEC_PCM, index.codec)
        return store.publish(recipe, dir, index)
    }

    @Test
    fun clipsAreStoredCompressedAndReadBackExactly() {
        val codec = FakeClipCodec()
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val dir = store.createVersion(recipe())
        val file = store.clipFile(dir, 0)
        store.writeClip(file, TtsPcmClip(24_000, pcm(300)))
        assertTrue(file.name.endsWith(".clip"))
        assertTrue(NarrationClipFormat.isCompressed(file.readBytes()))
        assertArrayEquals(pcm(300), store.readClip(file)!!.pcm)
        assertEquals(NarrationClipInfo(24_000, 300, compressed = true, codec = NarrationClipFormat.CODEC_AAC_LC), store.clipInfo(file))
        val index = store.indexFor(dir, recipe(), generatedAt = 1L)!!
        assertEquals(NarrationStore.CODEC_AAC, index.codec)
        assertEquals(listOf(12L), index.sentenceDurationsMs)
        assertEquals(file.length(), index.bytes)
    }

    @Test
    fun aClipTheCodecRefusesIsStoredAsPcm() {
        val codec = FakeClipCodec().apply { refuse = true }
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val dir = store.createVersion(recipe())
        store.writeClip(store.clipFile(dir, 0), TtsPcmClip(24_000, pcm(10)))
        assertFalse(NarrationClipFormat.isCompressed(store.clipFile(dir, 0).readBytes()))
        assertArrayEquals(pcm(10), store.readClip(store.clipFile(dir, 0))!!.pcm)
        assertEquals(NarrationStore.CODEC_PCM, store.indexFor(dir, recipe(), 1L)!!.codec)
    }

    @Test
    fun legacyPacksStillPlayAndMixedVersionsResolveBothNames() {
        val store = NarrationStore(folder.newFolder(), FakeClipCodec()).apply { load() }
        val two = recipe().copy(sentences = listOf("One.", "Two."))
        val dir = store.createVersion(two)
        // An update mid-chapter: sentence 0 was written as PCM before, sentence 1 is new.
        File(dir, "0000.pcm").writeBytes(NarrationClipFormat.legacy(24_000, pcm(48)))
        assertEquals("0000.pcm", store.clipFile(dir, 0).name)
        assertEquals("0001.clip", store.clipFile(dir, 1).name)
        store.writeClip(store.clipFile(dir, 1), TtsPcmClip(24_000, pcm(96)))
        assertArrayEquals(pcm(48), store.readClip(store.clipFile(dir, 0))!!.pcm)
        assertArrayEquals(pcm(96), store.readClip(store.clipFile(dir, 1))!!.pcm)
        val index = store.indexFor(dir, two, 1L)!!
        assertEquals(listOf(2L, 4L), index.sentenceDurationsMs)
        assertEquals(NarrationStore.CODEC_PCM, index.codec)
    }

    @Test
    fun indexRefusesMissingClipsAndMixedSampleRates() {
        val store = NarrationStore(folder.newFolder(), FakeClipCodec()).apply { load() }
        val two = recipe().copy(sentences = listOf("One.", "Two."))
        val dir = store.createVersion(two)
        store.writeClip(store.clipFile(dir, 0), TtsPcmClip(24_000, pcm(48)))
        assertNull(store.indexFor(dir, two, 1L))
        store.writeClip(store.clipFile(dir, 1), TtsPcmClip(22_050, pcm(48)))
        assertNull(store.indexFor(dir, two, 1L))
    }

    @Test
    fun copyingACompressedClipKeepsItsBytesAndAPcmOneIsCompressed() {
        val codec = FakeClipCodec()
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val dir = store.createVersion(recipe())
        val source = File(dir, "a.clip").also { store.writeClip(it, TtsPcmClip(24_000, pcm(100))) }
        val copy = File(dir, "b.clip")
        assertTrue(store.copyClip(source, copy))
        assertArrayEquals(source.readBytes(), copy.readBytes())
        assertEquals(1, codec.encodes)
        val legacy = File(dir, "c.pcm").apply { writeBytes(NarrationClipFormat.legacy(24_000, pcm(100))) }
        assertTrue(store.copyClip(legacy, File(dir, "d.clip")))
        assertEquals(2, codec.encodes)
        assertArrayEquals(pcm(100), store.readClip(File(dir, "d.clip"))!!.pcm)
        assertFalse(store.copyClip(File(dir, "missing.clip"), File(dir, "e.clip")))
    }

    @Test
    fun recompressingAPackReplacesItWithTheSameAudioCompressed() {
        val root = folder.newFolder()
        val store = NarrationStore(root, FakeClipCodec()).apply { load() }
        val two = recipe().copy(title = "Chapter 1", sentences = listOf("One.", "Two."))
        val legacy =
            store.createVersion(two).let { dir ->
                listOf("0000", "0001", "title").forEach { File(dir, "$it.pcm").writeBytes(NarrationClipFormat.legacy(24_000, pcm(480))) }
                store.publish(two, dir, store.indexFor(dir, two, 7L)!!)
            }
        val converted = store.recompress(legacy)!!
        assertEquals(converted.dir, store.pack("story:1", "chapter/1")!!.dir)
        assertFalse(legacy.dir.exists())
        assertEquals(NarrationStore.CODEC_AAC, converted.index.codec)
        assertEquals(legacy.index.sentenceDurationsMs, converted.index.sentenceDurationsMs)
        assertEquals(legacy.index.titleDurationMs, converted.index.titleDurationMs)
        assertEquals(7L, converted.index.generatedAt)
        assertEquals(two, converted.recipe)
        assertArrayEquals(pcm(480), store.readClip(store.titleFile(converted.dir))!!.pcm)
        assertArrayEquals(pcm(480), store.readClip(store.clipFile(converted.dir, 1))!!.pcm)
        assertEquals(converted.dir, NarrationStore(root, FakeClipCodec()).apply { load() }.pack("story:1", "chapter/1")!!.dir)
    }

    @Test
    fun recompressingKeepsTheVersionPlaybackHolds() {
        val store = NarrationStore(folder.newFolder(), FakeClipCodec()).apply { load() }
        val legacy = publishLegacy(store, recipe())
        store.retain(legacy)
        val converted = store.recompress(legacy)!!
        assertTrue(legacy.dir.exists())
        store.release(legacy)
        assertFalse(legacy.dir.exists())
        assertTrue(converted.dir.exists())
    }

    @Test
    fun aConversionTheCodecCannotFinishWritesNothing() {
        val codec = FakeClipCodec()
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val legacy = publishLegacy(store, recipe().copy(sentences = listOf("One.", "Two.")))
        codec.onEncode = { codec.refuse = true }
        assertNull(store.recompress(legacy))
        assertEquals(1, codec.encodes)
        assertEquals(legacy.dir, store.pack("story:1", "chapter/1")!!.dir)
        assertEquals(1, store.chapterDir("story:1", "chapter/1").listFiles { file -> file.isDirectory }!!.size)
    }

    @Test
    fun aConversionStopsAsSoonAsTheGeneratorHasWork() {
        val codec = FakeClipCodec()
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val legacy = publishLegacy(store, recipe().copy(sentences = listOf("One.", "Two.", "Three.")))
        var idle = true
        codec.onEncode = { idle = false }
        assertNull(store.recompress(legacy) { idle })
        assertEquals(1, codec.encodes)
        assertEquals(legacy.dir, store.pack("story:1", "chapter/1")!!.dir)
        assertEquals(1, store.chapterDir("story:1", "chapter/1").listFiles { file -> file.isDirectory }!!.size)
    }

    @Test
    fun playbackHoldingAConvertedPackReadsItsReplacement() {
        val store = NarrationStore(folder.newFolder(), FakeClipCodec()).apply { load() }
        val legacy = publishLegacy(store, recipe())
        val converted = store.recompress(legacy)!!
        assertEquals(converted.dir, store.retain(legacy)!!.dir)
        store.release(converted)
        assertTrue(converted.dir.exists())

        val regenerated = publish(store, recipe(text = "Hello again."))
        assertNull(store.retain(converted))
        assertEquals(regenerated.dir, store.retain(regenerated)!!.dir)
    }

    @Test
    fun recompressingNeverOverwritesARegenerationOrADeletion() {
        val codec = FakeClipCodec()
        val store = NarrationStore(folder.newFolder(), codec).apply { load() }
        val legacy = publishLegacy(store, recipe())
        var regenerated: NarrationPack? = null
        codec.onEncode = {
            codec.onEncode = {}
            regenerated = publish(store, recipe(text = "Hello again."))
        }
        assertNull(store.recompress(legacy))
        assertEquals(regenerated!!.dir, store.pack("story:1", "chapter/1")!!.dir)
        assertEquals(1, store.chapterDir("story:1", "chapter/1").listFiles { file -> file.isDirectory }!!.size)

        val other = publishLegacy(store, recipe(chapter = "chapter/2"))
        codec.onEncode = { store.deleteChapter("story:1", "chapter/2") }
        assertNull(store.recompress(other))
        assertNull(store.pack("story:1", "chapter/2"))
    }
}
