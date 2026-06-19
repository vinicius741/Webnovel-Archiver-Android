package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMimeTypesTest {
    @Test
    fun returnsSpecificMimeTypesForExportedFiles() {
        assertEquals("application/json", FileMimeTypes.forFilename("webnovel_backup.json"))
        assertEquals("application/zip", FileMimeTypes.forFilename("webnovel_full_backup.ZIP"))
        assertEquals("application/epub+zip", FileMimeTypes.forFilename("Story_Ch1-5.epub"))
        assertEquals("*/*", FileMimeTypes.forFilename("unknown.bin"))
    }
}
