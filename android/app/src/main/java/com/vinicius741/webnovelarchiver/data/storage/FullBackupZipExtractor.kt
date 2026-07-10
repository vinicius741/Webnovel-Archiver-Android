package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class ExtractedBackupEntries(
    val files: Set<String>,
)

internal object FullBackupZipExtractor {
    fun extract(
        zipFile: File,
        destination: File,
        usableBytes: Long,
    ): ExtractedBackupEntries {
        val state = ExtractionState(BackupInputLimits.extractionBudget(usableBytes))
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                state.register(entry)
                val output = extractionTarget(destination, entry)
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) { "Could not create restore directory ${entry.name}" }
                } else {
                    extractFile(zip, entry, output, state)
                }
                zip.closeEntry()
            }
        }
        return ExtractedBackupEntries(state.extractedFiles)
    }

    private fun extractionTarget(
        destination: File,
        entry: ZipEntry,
    ): File {
        check(BackupInputLimits.isAllowedFullBackupEntry(entry.name, entry.isDirectory)) {
            "Invalid full backup: unsupported ZIP entry ${entry.name}"
        }
        return ArchiveUtils.safeExtractionTarget(destination, entry.name)
            ?: error("Invalid full backup: unsafe ZIP entry ${entry.name}")
    }

    private fun extractFile(
        zip: ZipInputStream,
        entry: ZipEntry,
        output: File,
        state: ExtractionState,
    ) {
        state.extractedFiles += entry.name
        output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create restore directory ${it.name}" } }
        output.outputStream().use { sink ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                written += read
                state.totalUncompressed += read
                state.checkSizes(entry.name, written)
                sink.write(buffer, 0, read)
            }
        }
    }

    private class ExtractionState(
        private val maxTotalBytes: Long,
    ) {
        private var entryCount = 0
        private val seenEntries = mutableSetOf<String>()
        val extractedFiles = mutableSetOf<String>()
        var totalUncompressed = 0L

        fun register(entry: ZipEntry) {
            entryCount += 1
            check(entryCount <= BackupInputLimits.MAX_ZIP_ENTRIES) {
                "Backup has too many entries (>${BackupInputLimits.MAX_ZIP_ENTRIES})"
            }
            check(seenEntries.add(entry.name)) { "Invalid full backup: duplicate ZIP entry ${entry.name}" }
        }

        fun checkSizes(
            entryName: String,
            entryBytes: Long,
        ) {
            val maxEntryBytes =
                if (entryName == "manifest.json") BackupInputLimits.MAX_MANIFEST_BYTES else BackupInputLimits.MAX_ZIP_ENTRY_BYTES
            check(entryBytes <= maxEntryBytes) { "Backup entry too large: $entryName" }
            check(totalUncompressed <= maxTotalBytes) { "Backup uncompressed size exceeds available-space limit" }
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
