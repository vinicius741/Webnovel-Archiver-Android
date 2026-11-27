package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.ChapterPack
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.data.backup.RestoredChapterPackIndex
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a version-3 full backup's chapter files as one [ChapterPack] ZIP entry per story. */
internal object ChapterPackExport {
    /** One pack per story that has chapter files, in the order the stories first appear. */
    fun plan(chapterFiles: List<FullBackupChapterFile>): List<RestoredChapterPackIndex> =
        chapterFiles
            .map { it.storyId }
            .distinct()
            .map { storyId -> RestoredChapterPackIndex(storyId, FullBackupPaths.chapterPackPath(storyId)) }

    fun write(
        zip: ZipOutputStream,
        packs: List<RestoredChapterPackIndex>,
        chapterFiles: List<FullBackupChapterFile>,
        onChapterWritten: () -> Unit,
    ) {
        val chaptersByStory = chapterFiles.groupBy { it.storyId }
        // zstd output does not deflate; store it (level 0 still streams, unlike a STORED entry,
        // which needs its size and CRC before the data).
        zip.setLevel(Deflater.NO_COMPRESSION)
        packs.forEach { pack ->
            val directory = FullBackupPaths.chapterPackStoryDirectory(pack.path)
            zip.putNextEntry(ZipEntry(pack.path))
            ChapterPack.Writer(zip).use { writer ->
                chaptersByStory[pack.storyId].orEmpty().forEach { chapter ->
                    check(chapter.path.substringBeforeLast('/') == directory) { "Chapter ${chapter.path} is outside $directory" }
                    writer.add(chapter.path.substringAfterLast('/'), chapter.readContent())
                    onChapterWritten()
                }
            }
            zip.closeEntry()
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
    }

    private fun FullBackupChapterFile.readContent(): ByteArray =
        when {
            source != null -> source.readBytes()
            // R10: a downloaded chapter whose file is missing but whose legacy inline content
            // survives is materialized instead of dropped.
            inlineContent != null -> inlineContent.toByteArray(Charsets.UTF_8)
            else -> error("Chapter entry without content: $path")
        }
}
