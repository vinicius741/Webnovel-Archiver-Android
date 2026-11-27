package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupExportPlanning
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.data.backup.QuickBackupFormat
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a quick backup (`QuickBackupFormat`): the library's metadata with every chapter list,
 * bookmark and downloaded flag, the same settings a full backup carries, trend history, and, when
 * asked, the applied AI covers. No chapter text, EPUBs or audio.
 */
internal class QuickBackupExporter(
    private val storage: AppStorage,
) {
    fun export(
        includeCovers: Boolean,
        onProgress: (String) -> Unit = {},
    ): File {
        val library = storage.getLibrary()
        BackupExportPlanning.validateFullBackup(library.size)?.let { error(it) }
        onProgress("Backing up ${library.size} novels…")
        val metrics =
            library.mapNotNull { story ->
                storage
                    .metricFile(
                        story.id,
                    ).takeIf(File::isFile)
                    ?.let { QuickBackupFile(story.id, FullBackupPaths.metricPath(story.id), it) }
            }
        val covers =
            if (includeCovers) {
                library.mapNotNull { story ->
                    val path = story.aiCoverPath?.takeIf { it.startsWith("covers/") && QuickBackupFormat.isAllowedEntry(it) }
                    val file = path?.let(storage::resolveAbsolutePath)?.takeIf(File::isFile)
                    file?.let { QuickBackupFile(story.id, path, it) }
                }
            } else {
                emptyList()
            }
        val manifest =
            mapOf(
                "format" to QuickBackupFormat.FORMAT,
                "version" to QuickBackupFormat.VERSION,
                "exportDate" to Instant.now().toString(),
                "library" to library.map(::withoutLocalFiles),
                "config" to BackupExporter(storage).fullConfig(),
                "metricFiles" to metrics.map(QuickBackupFile::indexEntry),
                "coverFiles" to covers.map(QuickBackupFile::indexEntry),
            )
        val output = File(storage.backupRoot, "${QuickBackupFormat.FILE_PREFIX}${System.currentTimeMillis()}.zip")
        AtomicFileWrites.writeAtomically(output) { stream ->
            ZipOutputStream(stream).use { zip ->
                // The manifest goes first: it is how Restore tells a quick backup from a full one.
                zip.putNextEntry(ZipEntry(QuickBackupFormat.MANIFEST_ENTRY))
                zip.write(SharedGson.plain.toJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                (metrics + covers).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.path))
                    file.source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return output
    }

    /** Keeps which chapters were downloaded (restore offers the missing ones again) but no text or file paths. */
    private fun withoutLocalFiles(story: Story): Story =
        story.copy(
            chapters = story.chapters.map { it.copy(filePath = null, content = null) }.toMutableList(),
            epubPath = null,
            epubPaths = null,
        )
}

private class QuickBackupFile(
    val storyId: String,
    val path: String,
    val source: File,
) {
    fun indexEntry(): Map<String, String> = mapOf("storyId" to storyId, "path" to path)
}
