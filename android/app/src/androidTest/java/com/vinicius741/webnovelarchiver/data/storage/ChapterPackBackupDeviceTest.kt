package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.data.backup.ChapterPack
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.simulation.SimulationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Full-backup round trips through the real exporter and restorer with the Android zstd library.
 * Runs only in the instrumentation sandbox, on a scoped storage root, never the debug library.
 */
@RunWith(AndroidJUnit4::class)
class ChapterPackBackupDeviceTest {
    private fun storage(): AppStorage {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return AppStorage(SimulationContext(base, "chapter-pack-${System.nanoTime()}"))
    }

    private fun seed(storage: AppStorage): Story {
        val chapters =
            (0 until 3).map { Chapter(id = "c$it", title = "Chapter $it", url = "https://example.invalid/$it") }.toMutableList()
        chapters +=
            Chapter(id = "inline", title = "Legacy", url = "https://example.invalid/i", downloaded = true, content = "<p>inline</p>")
        chapters += Chapter(id = "never", title = "Not downloaded", url = "https://example.invalid/n")
        val story =
            Story(
                id = "rr_pack test",
                title = "Pack fixture",
                author = "Device test",
                sourceUrl = "https://example.invalid",
                chapters = chapters,
            )
        chapters.take(3).forEachIndexed { index, chapter ->
            chapter.filePath = storage.saveChapter(story.id, index, chapter, "<p>Chapter $index</p>".repeat(50))
            chapter.downloaded = true
        }
        storage.addOrUpdateStory(story)
        return story
    }

    private fun assertRestored(
        storage: AppStorage,
        zip: File,
    ) {
        storage.deleteStory("rr_pack test")
        val result = storage.importFullBackupUri(Uri.fromFile(zip))
        assertFalse(result, result.startsWith("Restore failed"))
        val restored = checkNotNull(storage.getStory("rr_pack test"))
        assertEquals(listOf(true, true, true, true, false), restored.chapters.map { it.downloaded })
        (0 until 3).forEach { assertEquals("<p>Chapter $it</p>".repeat(50), storage.readChapter(restored.chapters[it])) }
        assertEquals("<p>inline</p>", storage.readChapter(restored.chapters[3]))
    }

    @Test
    fun exportWritesOnePackPerStoryAndRestoresIt() {
        val storage = storage()
        seed(storage)

        val zip = storage.exportFullBackup()
        val names = ZipFile(zip).use { file -> file.entries().toList().map { it.name } }
        val manifest =
            ZipFile(zip).use { file ->
                JsonParser.parseString(file.getInputStream(file.getEntry("manifest.json")).reader().readText()).asJsonObject
            }

        assertEquals(listOf("manifest.json", "chapter_packs/rr_pack%20test.zst"), names)
        assertEquals(3, manifest["version"].asInt)
        assertEquals(4, manifest["chapterFiles"].asJsonArray.size())
        assertRestored(storage, zip)
    }

    @Test
    fun restoreStillReadsVersionOneLooseChapters() {
        val storage = storage()
        seed(storage)
        val packed = storage.exportFullBackup()
        val loose = File(packed.parentFile, "loose_${System.nanoTime()}.zip")
        ZipFile(packed).use { source ->
            ZipOutputStream(loose.outputStream()).use { out ->
                val manifest =
                    JsonParser
                        .parseString(
                            source.getInputStream(source.getEntry("manifest.json")).reader().readText(),
                        ).asJsonObject
                manifest.addProperty("version", 1)
                manifest.remove("chapterPacks")
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(manifest.toString().toByteArray())
                out.closeEntry()
                source.entries().toList().filter { it.name.startsWith("chapter_packs/") }.forEach { pack ->
                    val directory = "novels/" + pack.name.removePrefix("chapter_packs/").removeSuffix(".zst")
                    source.getInputStream(pack).use { input ->
                        ChapterPack.read(input, Long.MAX_VALUE) { name, _, content ->
                            out.putNextEntry(ZipEntry("$directory/$name"))
                            content.copyTo(out)
                            out.closeEntry()
                        }
                    }
                }
            }
        }
        assertTrue(ZipFile(loose).use { file -> file.entries().toList().none { it.name.startsWith("chapter_packs/") } })

        assertRestored(storage, loose)
    }
}
