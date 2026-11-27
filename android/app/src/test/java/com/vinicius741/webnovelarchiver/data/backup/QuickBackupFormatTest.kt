package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBackupFormatTest {
    private fun manifest(
        library: List<Map<String, Any?>> = listOf(mapOf("id" to "s1", "title" to "One")),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "format" to QuickBackupFormat.FORMAT,
            "version" to 1.0,
            "library" to library,
            "config" to mapOf("tabs" to emptyList<Any>()),
        ) + extra

    @Test
    fun aWellFormedManifestParses() {
        val parsed =
            QuickBackupFormat.parseValidated(
                Gson(),
                manifest(
                    extra =
                        mapOf(
                            "exportDate" to "2026-11-27T10:00:00Z",
                            "metricFiles" to listOf(mapOf("storyId" to "s1", "path" to "metrics/s1.json")),
                            "coverFiles" to listOf(mapOf("storyId" to "s1", "path" to "covers/s1.jpg")),
                        ),
                ),
            )
        assertEquals("One", parsed.library.single().title)
        assertTrue(
            "a story without chapters reads as an empty list",
            parsed.library
                .single()
                .chapters
                .isEmpty(),
        )
        assertEquals(listOf(RestoredMetricFileIndex("s1", "metrics/s1.json")), parsed.metricFiles)
        assertEquals(listOf(RestoredCoverFileIndex("s1", "covers/s1.jpg")), parsed.coverFiles)
        assertEquals("2026-11-27T10:00:00Z", parsed.exportDate)
    }

    @Test
    fun aFullBackupOrAnUnknownVersionIsRejected() {
        assertEquals(
            "Invalid quick backup: unsupported format",
            QuickBackupFormat.validate(manifest() + ("format" to "webnovel-archiver-full-backup")),
        )
        assertEquals("Invalid quick backup: unsupported version 2", QuickBackupFormat.validate(manifest() + ("version" to 2.0)))
        assertEquals("Invalid quick backup: missing version", QuickBackupFormat.validate(manifest() + ("version" to 1.5)))
        assertEquals("Invalid quick backup: missing configuration", QuickBackupFormat.validate(manifest() + ("config" to null)))
        assertNull(QuickBackupFormat.validate(manifest()))
    }

    @Test
    fun storiesNeedUniqueIds() {
        assertEquals(
            "Invalid quick backup: duplicate story IDs",
            QuickBackupFormat.validate(manifest(library = listOf(mapOf("id" to "a"), mapOf("id" to "a")))),
        )
        assertEquals(
            "Invalid quick backup: malformed story data",
            QuickBackupFormat.validate(manifest(library = listOf(mapOf("id" to " ")))),
        )
        assertEquals(
            "Invalid quick backup: malformed story data",
            QuickBackupFormat.validate(manifest(library = listOf(mapOf("title" to "x")))),
        )
    }

    @Test
    fun fileIndexesStayInTheirTreesAndBelongToAStory() {
        fun covers(vararg entries: Pair<String, String>) =
            QuickBackupFormat.validate(
                manifest(
                    extra =
                        mapOf("coverFiles" to entries.map { mapOf("storyId" to it.first, "path" to it.second) }),
                ),
            )

        assertEquals("Invalid quick backup: malformed cover file index", covers("s1" to "metrics/s1.json"))
        assertEquals("Invalid quick backup: malformed cover file index", covers("s1" to "covers/../settings.json"))
        assertEquals("Invalid quick backup: malformed cover file index", covers("s1" to "covers/a/b.jpg"))
        assertEquals("Invalid quick backup: malformed cover file index", covers("ghost" to "covers/s1.jpg"))
        assertEquals("Invalid quick backup: too many cover files", covers("s1" to "covers/a.jpg", "s1" to "covers/b.jpg"))
        assertNull(covers("s1" to "covers/s1.webp"))
        assertEquals(
            "Invalid quick backup: malformed metric file index",
            QuickBackupFormat.validate(
                manifest(
                    extra =
                        mapOf("metricFiles" to listOf(mapOf("storyId" to "s1", "path" to "novels/s1/1.html"))),
                ),
            ),
        )
    }

    @Test
    fun onlyTheManifestMetricsAndCoversAreAllowedEntries() {
        assertTrue(QuickBackupFormat.isAllowedEntry("quick_backup.json"))
        assertTrue(QuickBackupFormat.isAllowedEntry("metrics/s1.json"))
        assertTrue(QuickBackupFormat.isAllowedEntry("covers/s1.png"))
        assertFalse(QuickBackupFormat.isAllowedEntry("manifest.json"))
        assertFalse(QuickBackupFormat.isAllowedEntry("novels/s1/0001_c1.html"))
        assertFalse(QuickBackupFormat.isAllowedEntry("settings.json"))
        assertFalse(QuickBackupFormat.isAllowedEntry("metrics/../settings.json"))
    }
}
