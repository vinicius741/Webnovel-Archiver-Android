package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.simulation.SimulationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Quick backup and restore end to end, in the instrumentation sandbox: a scoped storage root, never the debug library. */
@RunWith(AndroidJUnit4::class)
class QuickBackupDeviceTest {
    private fun storage(): AppStorage {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return AppStorage(SimulationContext(base, "quick-${System.nanoTime()}"))
    }

    private fun AppStorage.downloaded(
        storyId: String,
        index: Int,
        id: String,
        text: String,
    ): Chapter {
        val chapter = Chapter(id = id, title = id, url = "https://example.com/$id")
        return chapter.copy(downloaded = true, filePath = saveChapter(storyId, index, chapter, text), downloadedAt = 1L)
    }

    @Test
    fun restoringAQuickBackupReplacesMetadataAndKeepsChapterText() {
        val storage = storage()
        val c1 = storage.downloaded("rr_1", 0, "c1", "<p>one</p>")
        val c2 = storage.downloaded("rr_1", 1, "c2", "<p>two</p>")
        val c3 = Chapter(id = "c3", title = "c3", url = "https://example.com/c3")
        storage.saveLibrary(
            listOf(Story(id = "rr_1", title = "Backed up", tabId = "t1", lastReadChapterId = "c2", chapters = mutableListOf(c1, c2, c3))),
        )
        storage.tabsDoc.set(listOf(Tab(id = "t1", name = "Reading", order = 0)))
        storage.appendMetricSnapshot("rr_1", StoryMetricSnapshot(capturedAt = 1_000L))

        val zip = storage.exportQuickBackup(includeCovers = false)
        val names = ZipFile(zip).use { file -> file.entries().toList().map(ZipEntry::getName) }
        assertEquals("quick_backup.json", names.first())
        assertTrue("no chapter text in a quick backup", names.none { it.startsWith("novels/") })
        assertTrue(storage.isQuickBackupUri(Uri.fromFile(zip)))

        // After the backup: c2's text is lost, c3 is downloaded, the story is renamed, a story and a tab are added.
        File(storage.root, checkNotNull(c2.filePath)).delete()
        val c3Downloaded = storage.downloaded("rr_1", 2, "c3", "<p>three</p>")
        val later = storage.downloaded("rr_2", 0, "x1", "<p>mine</p>")
        storage.saveLibrary(
            listOf(
                Story(
                    id = "rr_1",
                    title = "Renamed",
                    tabId = "t2",
                    lastReadChapterId = "c3",
                    chapters = mutableListOf(c1, c2, c3Downloaded),
                ),
                Story(id = "rr_2", title = "Added later", chapters = mutableListOf(later)),
            ),
        )
        storage.tabsDoc.set(listOf(Tab(id = "t1", name = "Reading", order = 0), Tab(id = "t2", name = "New", order = 1)))

        val outcome = storage.importQuickBackupUri(Uri.fromFile(zip))
        storage.reloadJsonDocuments()

        assertEquals(
            "Restored quick backup: 1 novel. Chapter text on this phone was kept; 1 chapter can be downloaded again.",
            outcome,
        )
        val library = storage.getLibrary().associateBy { it.id }
        val story = library.getValue("rr_1")
        assertEquals("Backed up", story.title)
        assertEquals("c2", story.lastReadChapterId)
        assertEquals("t1", story.tabId)
        assertEquals(listOf(true, false, true), story.chapters.map { it.downloaded })
        assertEquals("<p>one</p>", storage.readChapter(story.chapters[0]))
        assertNull(story.chapters[1].filePath)
        assertEquals("downloaded after the backup, kept", "<p>three</p>", storage.readChapter(story.chapters[2]))
        assertEquals("<p>mine</p>", storage.readChapter(library.getValue("rr_2").chapters.single()))
        assertEquals(listOf("t1", "t2"), storage.tabsDoc.get().map { it.id })
        assertEquals(listOf(1_000L), storage.getMetricHistory("rr_1").snapshots.map { it.capturedAt })
        assertTrue(
            "nothing left behind",
            storage.context.filesDir
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith("webnovel_quick_restore") },
        )
    }

    @Test
    fun aFullBackupIsNotTakenForAQuickOne() {
        val storage = storage()
        storage.saveLibrary(
            listOf(Story(id = "rr_1", title = "One", chapters = mutableListOf(storage.downloaded("rr_1", 0, "c1", "<p>one</p>")))),
        )

        assertFalse(storage.isQuickBackupUri(Uri.fromFile(storage.exportFullBackup())))
    }

    @Test
    fun aRejectedQuickBackupChangesNothing() {
        val storage = storage()
        val chapter = storage.downloaded("rr_1", 0, "c1", "<p>one</p>")
        storage.saveLibrary(listOf(Story(id = "rr_1", title = "Mine", chapters = mutableListOf(chapter))))
        val bad = File(storage.context.cacheDir, "bad_quick.zip")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("quick_backup.json"))
            zip.write(
                """{"format":"webnovel-archiver-quick-backup","version":1,"library":[{"id":"a"},{"id":"a"}],"config":{}}""".toByteArray(),
            )
            zip.closeEntry()
        }

        val outcome = storage.importQuickBackupUri(Uri.fromFile(bad))

        assertEquals("Restore failed: Invalid quick backup: duplicate story IDs. Your library was not changed.", outcome)
        assertEquals("Mine", storage.getLibrary().single().title)
        assertEquals(
            "<p>one</p>",
            storage.readChapter(
                storage
                    .getLibrary()
                    .single()
                    .chapters
                    .single(),
            ),
        )
    }
}
