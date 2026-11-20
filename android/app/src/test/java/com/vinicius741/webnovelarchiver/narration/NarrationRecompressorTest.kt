package com.vinicius741.webnovelarchiver.narration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NarrationRecompressorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val voice = NarrationVoice("engine", "voice", 1f, "Voice")

    private fun legacyPack(
        store: NarrationStore,
        chapter: String,
        frames: Int,
        rate: Int = 24_000,
    ): NarrationPack {
        val recipe =
            NarrationRecipe(
                storyId = "story",
                chapterId = chapter,
                chunkingVersion = 2,
                textFingerprint = chapter,
                sentences = listOf("One.", "Two."),
                voice = voice,
                voiceKey = "key",
            )
        val dir = store.createVersion(recipe)
        listOf("0000", "0001").forEach { File(dir, "$it.pcm").writeBytes(NarrationClipFormat.legacy(rate, ByteArray(frames * 2) { 1 })) }
        return store.publish(recipe, dir, store.indexFor(dir, recipe, 0L)!!)
    }

    @Test
    fun convertsEveryLegacyPackLargestFirstAfterWaitingForIdle() =
        runTest {
            val store = NarrationStore(folder.newFolder(), FakeClipCodec()).apply { load() }
            legacyPack(store, "small", frames = 100)
            legacyPack(store, "large", frames = 900)
            val order = mutableListOf<String>()
            var waits = 0
            NarrationRecompressor(store) {
                waits++
                store.allPacks().filter { it.index.codec == NarrationStore.CODEC_PCM }.maxByOrNull { it.index.bytes }?.let {
                    order +=
                        it.chapterId
                }
            }.run()
            assertEquals(listOf("large", "small"), order)
            assertTrue(waits >= 2)
            assertTrue(store.allPacks().all { it.index.codec == NarrationStore.CODEC_AAC })
        }

    @Test
    fun stopsAfterRepeatedFailuresAndLeavesThePacksPlayable() =
        runTest {
            val codec = FakeClipCodec().apply { refuse = true }
            val store = NarrationStore(folder.newFolder(), codec).apply { load() }
            val packs = (1..5).map { legacyPack(store, "c$it", frames = 100 + it) }
            NarrationRecompressor(store) {}.run()
            assertEquals(packs.map { it.dir }.toSet(), store.allPacks().map { it.dir }.toSet())
            assertTrue(packs.all { it.dir.isDirectory })
            assertEquals(1, store.chapterDir("story", "c1").listFiles { file -> file.isDirectory }!!.size)
        }

    @Test
    fun aChapterGenerationInterruptsIsConvertedOnceIdleAndNotCountedAsFailing() =
        runTest {
            val codec = FakeClipCodec()
            val store = NarrationStore(folder.newFolder(), codec).apply { load() }
            (1..4).forEach { legacyPack(store, "c$it", frames = 100 + it) }
            // Generation starts after each of the first three clips; waiting for idle lets it finish.
            var interruptions = 3
            var busy = false
            codec.onEncode = { busy = interruptions-- > 0 }
            NarrationRecompressor(store, isIdle = { !busy }) { busy = false }.run()
            // Three interruptions counted as failures would have stopped the pass with packs left as PCM.
            assertTrue(store.allPacks().all { it.index.codec == NarrationStore.CODEC_AAC })
            assertEquals(4, store.allPacks().size)
        }

    @Test
    fun packsThatCannotBeConvertedNeverHoldBackTheRest() =
        runTest {
            val codec = FakeClipCodec().apply { sampleRates = setOf(24_000) }
            val store = NarrationStore(folder.newFolder(), codec).apply { load() }
            // The three largest packs are at a rate AAC cannot take; three codec failures would stop the pass.
            (1..3).forEach { legacyPack(store, "odd$it", frames = 1_000 + it, rate = 23_456) }
            (1..2).forEach { legacyPack(store, "ok$it", frames = 100 + it) }
            NarrationRecompressor(store) {}.run()
            assertTrue(store.allPacks().filter { it.chapterId.startsWith("ok") }.all { it.index.codec == NarrationStore.CODEC_AAC })
            assertTrue(store.allPacks().filter { it.chapterId.startsWith("odd") }.all { it.index.codec == NarrationStore.CODEC_PCM })
        }

    @Test
    fun doesNothingWithoutACodec() =
        runTest {
            val store = NarrationStore(folder.newFolder()).apply { load() }
            val pack = legacyPack(store, "c", frames = 100)
            NarrationRecompressor(store) { error("must not wait") }.run()
            assertEquals(pack.dir, store.pack("story", "c")!!.dir)
        }
}
