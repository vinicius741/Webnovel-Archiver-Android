package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.simulation.SimulationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs only in the instrumentation sandbox: a scoped storage root, never the debug library. */
@RunWith(AndroidJUnit4::class)
class AdoptChapterFileDeviceTest {
    private fun storage(): AppStorage {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return AppStorage(SimulationContext(base, "adopt-${System.nanoTime()}"))
    }

    private fun source(
        storage: AppStorage,
        name: String,
        text: String,
    ): String =
        File(storage.context.cacheDir, "adopt-src-${System.nanoTime()}-$name.html")
            .apply { writeText(text) }
            .absolutePath

    @Test
    fun idsThatSanitizeToTheSameNameNeverOverwriteEachOther() {
        val storage = storage()
        val first = Chapter(id = "a/b", url = "u1", downloaded = true, filePath = source(storage, "1", "first text"))
        val second = Chapter(id = "a?b", url = "u2", downloaded = true, filePath = source(storage, "2", "second text"))

        val firstPath = checkNotNull(storage.adoptChapterFile("rr_1", first))
        val secondPath = checkNotNull(storage.adoptChapterFile("rr_1", second))

        assertNotEquals(firstPath, secondPath)
        assertEquals("first text", storage.readChapter(first.copy(filePath = firstPath)))
        assertEquals("second text", storage.readChapter(second.copy(filePath = secondPath)))
    }

    @Test
    fun adoptingTheSameTextAgainReusesTheExistingFile() {
        val storage = storage()
        val chapter = Chapter(id = "42", url = "u", downloaded = true, filePath = source(storage, "a", "same text"))
        val again = chapter.copy(filePath = source(storage, "b", "same text"))

        assertEquals(storage.adoptChapterFile("rr_1", chapter), storage.adoptChapterFile("rr_1", again))
    }
}
