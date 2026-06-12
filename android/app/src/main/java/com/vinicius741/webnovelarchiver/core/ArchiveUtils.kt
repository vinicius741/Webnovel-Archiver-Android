package com.vinicius741.webnovelarchiver.core

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArchiveUtils {
    fun safeExtractionTarget(root: File, entryName: String): File? {
        val normalizedEntry = entryName.replace('\\', '/')
        if (normalizedEntry.startsWith("/") || normalizedEntry.contains('\u0000')) return null

        val target = File(root, normalizedEntry)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        return if (targetPath.startsWith(rootPath)) target else null
    }

    fun putStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}
