package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.data.backup.ChapterPack
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** [files] holds every extracted entry name; a version-3 chapter pack contributes its own name and each chapter file it held. */
internal data class ExtractedBackupEntries(
    val files: Set<String>,
)

internal object FullBackupZipExtractor {
    fun extract(
        zipFile: File,
        destination: File,
        usableBytes: Long,
        budgetBytes: Long = BackupInputLimits.extractionBudget(usableBytes),
    ): ExtractedBackupEntries {
        val state = ExtractionState(budgetBytes)
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                state.register(entry.name)
                val output = extractionTarget(destination, entry.name, entry.isDirectory)
                when {
                    entry.isDirectory -> check(output.mkdirs() || output.isDirectory) { "Could not create restore directory ${entry.name}" }
                    FullBackupPaths.isChapterPackPath(entry.name) -> extractChapterPack(zip, entry.name, destination, state)
                    else -> extractFile(zip, entry.name, output, state)
                }
                zip.closeEntry()
            }
        }
        return ExtractedBackupEntries(state.extractedFiles)
    }

    private fun extractionTarget(
        destination: File,
        name: String,
        directory: Boolean,
    ): File {
        check(BackupInputLimits.isAllowedFullBackupEntry(name, directory)) {
            "Invalid full backup: unsupported ZIP entry $name"
        }
        return ArchiveUtils.safeExtractionTarget(destination, name)
            ?: error("Invalid full backup: unsafe ZIP entry $name")
    }

    /**
     * Expands a version-3 chapter pack into `novels/<story>/` as if each chapter were its own ZIP
     * entry: every file passes the same allowlist, duplicate, entry-count, entry-size and total-size
     * checks, so the zstd stream cannot inflate past the limits a plain ZIP is held to.
     */
    private fun extractChapterPack(
        zip: ZipInputStream,
        packName: String,
        destination: File,
        state: ExtractionState,
    ) {
        state.extractedFiles += packName
        val directory = FullBackupPaths.chapterPackStoryDirectory(packName)
        ChapterPack.read(zip, BackupInputLimits.MAX_ZIP_ENTRY_BYTES) { name, _, content ->
            val path = "$directory/$name"
            state.register(path)
            extractFile(content, path, extractionTarget(destination, path, directory = false), state)
        }
        // Reaching the end of the entry also makes ZipInputStream verify the pack's CRC.
        if (zip.read(ByteArray(BUFFER_BYTES)) >= 0) throw ZipException("Corrupt chapter pack: trailing data in $packName")
    }

    private fun extractFile(
        input: InputStream,
        name: String,
        output: File,
        state: ExtractionState,
    ) {
        state.extractedFiles += name
        output.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create restore directory ${it.name}" } }
        output.outputStream().use { sink ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                written += read
                state.totalUncompressed += read
                state.checkSizes(name, written)
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

        fun register(name: String) {
            entryCount += 1
            check(entryCount <= BackupInputLimits.MAX_ZIP_ENTRIES) {
                "Backup has too many entries (>${BackupInputLimits.MAX_ZIP_ENTRIES})"
            }
            check(seenEntries.add(name)) { "Invalid full backup: duplicate ZIP entry $name" }
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
