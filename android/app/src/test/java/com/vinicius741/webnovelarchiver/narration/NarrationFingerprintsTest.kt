package com.vinicius741.webnovelarchiver.narration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class NarrationFingerprintsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stamps = mutableMapOf("c1" to "s1", "c2" to "s2", "c3" to null)
    private val reads = AtomicInteger()

    private fun NarrationFingerprints.read(
        ids: List<String> = listOf("c1", "c2", "c3"),
        keep: (String, String) -> Boolean = { _, _ -> true },
    ): Map<String, String?> =
        runBlocking {
            of(
                storyId = "story",
                chapterIds = ids,
                stampOf = { stamps[it] },
                compute = { id ->
                    reads.incrementAndGet()
                    "fp-$id-${stamps[id]}"
                },
                keep = keep,
            )
        }

    @Test
    fun reusesFingerprintsWhileTheStampIsUnchanged() {
        val cache = NarrationFingerprints(File(folder.root, "fp.txt"))
        assertEquals(mapOf("c1" to "fp-c1-s1", "c2" to "fp-c2-s2", "c3" to null), cache.read())
        assertEquals(2, reads.get())

        cache.read()
        assertEquals(2, reads.get())

        stamps["c2"] = "s2b"
        assertEquals("fp-c2-s2b", cache.read()["c2"])
        assertEquals(3, reads.get())
    }

    @Test
    fun survivesARestartThroughItsFile() {
        val file = File(folder.root, "fp.txt")
        NarrationFingerprints(file).read()
        reads.set(0)

        val restarted = NarrationFingerprints(file)
        assertEquals("fp-c1-s1", restarted.read()["c1"])
        assertEquals(0, reads.get())
    }

    @Test
    fun anotherAppBuildStartsOver() {
        val file = File(folder.root, "fp.txt")
        NarrationFingerprints(file, build = "41").read()
        reads.set(0)

        NarrationFingerprints(file, build = "42").read()
        assertEquals(2, reads.get())
    }

    @Test
    fun savesOnlyEntriesStillWanted() {
        val file = File(folder.root, "fp.txt")
        NarrationFingerprints(file).read(keep = { _, chapterId -> chapterId != "c2" })
        assertFalse(file.readText().contains("fp-c2"))
        assertEquals(true, file.readText().contains("fp-c1"))
    }

    @Test
    fun ignoresACorruptFile() {
        val file = File(folder.root, "fp.txt").apply { writeText("garbage\nnot\tenough") }
        val cache = NarrationFingerprints(file)
        assertEquals("fp-c1-s1", cache.read()["c1"])
        assertNull(cache.read()["c3"])
    }
}
