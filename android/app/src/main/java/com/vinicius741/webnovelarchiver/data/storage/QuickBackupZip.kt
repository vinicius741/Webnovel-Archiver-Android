package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.data.backup.QuickBackupFormat
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A quick backup read off its ZIP: the manifest text and the metric/cover files staged on disk. */
internal class QuickBackupEntries(
    val manifestText: String,
    /** ZIP paths of the staged files (`metrics/…`, `covers/…`), each at `<staging>/<path>`. */
    val files: Set<String>,
)

/** Reads quick-backup ZIPs as a stream: no temporary copy of the input, chapter text never involved. */
internal object QuickBackupZip {
    /** All metric and cover files together; a quick backup is a few MB, or tens with AI covers. */
    const val MAX_TOTAL_BYTES = 1_000_000_000L

    /** Whether [input] is a quick backup: its first ZIP entry is the quick manifest. Never throws on a bad ZIP. */
    fun isQuickBackup(input: InputStream): Boolean =
        try {
            ZipInputStream(input).use { it.nextEntry?.name == QuickBackupFormat.MANIFEST_ENTRY }
        } catch (_: ZipException) {
            false
        } catch (_: IOException) {
            false
        }

    /**
     * Reads [input] into [staging]. The manifest must come first; every other entry must be a
     * `metrics/` or `covers/` file the format allows, once each, within the size limits.
     */
    fun read(
        input: InputStream,
        staging: File,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): QuickBackupEntries {
        val reader = EntryReader(staging, maxTotalBytes)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && QuickBackupFormat.isAllowedEntry(entry.name)) {
                    "Invalid quick backup: unsupported ZIP entry ${entry.name}"
                }
                reader.read(zip, entry.name)
                zip.closeEntry()
            }
        }
        return QuickBackupEntries(reader.manifest ?: error("Invalid quick backup: missing manifest"), reader.files)
    }

    private class EntryReader(
        private val staging: File,
        private val maxTotalBytes: Long,
    ) {
        var manifest: String? = null
        val files = LinkedHashSet<String>()
        private var total = 0L

        fun read(
            zip: ZipInputStream,
            name: String,
        ) {
            if (name == QuickBackupFormat.MANIFEST_ENTRY) {
                check(manifest == null && files.isEmpty()) { MANIFEST_FIRST }
                val text = BackupInputLimits.readUtf8(zip, BackupInputLimits.MAX_MANIFEST_BYTES, "Quick-backup manifest")
                manifest = text
                total += text.length
                return
            }
            check(manifest != null) { MANIFEST_FIRST }
            check(files.add(name)) { "Invalid quick backup: duplicate ZIP entry $name" }
            check(files.size <= BackupInputLimits.MAX_ZIP_ENTRIES) { "Invalid quick backup: too many entries" }
            val target = ArchiveUtils.safeExtractionTarget(staging, name) ?: error("Invalid quick backup: unsafe ZIP entry $name")
            target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create restore directory" } }
            val limit = minOf(BackupInputLimits.MAX_ZIP_ENTRY_BYTES, maxTotalBytes - total)
            total += target.outputStream().use { BackupInputLimits.copyWithLimit(zip, it, limit, "Quick-backup entry $name") }
        }
    }

    private const val MANIFEST_FIRST = "Invalid quick backup: the manifest must come first, once"
}
