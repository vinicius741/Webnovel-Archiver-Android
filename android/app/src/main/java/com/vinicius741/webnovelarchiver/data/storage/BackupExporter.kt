package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupExportPlanning
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BackupExporter(
    private val storage: AppStorage,
) {
    private val gson get() = storage.gson

    fun exportJson(): File {
        val sourceLibrary = storage.getLibrary()
        val library = sourceLibrary.map(::storyWithoutLocalFiles)
        val payload =
            mapOf(
                "version" to 2,
                "exportDate" to Instant.now().toString(),
                "library" to library,
                "tabs" to storage.getTabs(),
            )
        val json = gson.toJson(payload)
        BackupExportPlanning.validateJsonBackup(sourceLibrary.size, json.toByteArray().size.toLong())?.let { error(it) }
        return File(storage.backupRoot, "webnovel_backup_${System.currentTimeMillis()}.json").also {
            AtomicFileWrites.writeText(it, json)
        }
    }

    fun exportCleanupRules(): File {
        val payload =
            mapOf(
                "version" to 1,
                "exportDate" to Instant.now().toString(),
                "sentenceRemovalList" to storage.getSentenceRemovalList(),
                "regexCleanupRules" to storage.getRegexRules(),
            )
        return File(storage.backupRoot, "webnovel_cleanup_rules_${System.currentTimeMillis()}.json").also {
            AtomicFileWrites.writeText(it, gson.toJson(payload))
        }
    }

    fun exportFull(): File {
        val library = storage.getLibrary()
        BackupExportPlanning.validateFullBackup(library.size)?.let { error(it) }
        val chapterFiles = collectChapterFiles(library)
        val metricFiles = collectMetricFiles(library)
        val manifest = fullManifest(library, chapterFiles, metricFiles)
        return File(storage.backupRoot, "webnovel_full_backup_${System.currentTimeMillis()}.zip").also { output ->
            AtomicFileWrites.stream(output) { stream ->
                ZipOutputStream(stream).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(gson.toJson(manifest).toByteArray())
                    zip.closeEntry()
                    chapterFiles.forEach { chapter ->
                        zip.putNextEntry(ZipEntry(chapter.path))
                        chapter.source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    metricFiles.forEach { metric ->
                        zip.putNextEntry(ZipEntry(metric.path))
                        metric.source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun fullManifest(
        library: List<Story>,
        chapterFiles: List<FullBackupChapterFile>,
        metricFiles: List<FullBackupMetricFile>,
    ): Map<String, Any?> =
        mapOf(
            "format" to "webnovel-archiver-full-backup",
            "version" to 1,
            "exportDate" to Instant.now().toString(),
            "library" to library.map(::storyWithoutTransientPaths),
            "config" to fullConfig(),
            "chapterFiles" to
                chapterFiles.map {
                    mapOf(
                        "storyId" to it.storyId,
                        "chapterId" to it.chapterId,
                        "chapterIndex" to it.chapterIndex,
                        "title" to it.title,
                        "path" to it.path,
                    )
                },
            // Per-story trend history (`metrics/<id>.json`). Optional on restore: older backups omit
            // the key and restore with empty history (the storage layer's missing-file path).
            "metricFiles" to metricFiles.map { mapOf("storyId" to it.storyId, "path" to it.path) },
        )

    private fun fullConfig(): Map<String, Any?> {
        val displayPreferences = storage.getDisplayPreferences()
        return mapOf(
            "settings" to storage.getSettings(),
            "sourceDownloadSettings" to storage.getSourceDownloadSettings(),
            "chapterFilterSettings" to storage.getChapterFilterSettings(),
            "displayPreferences" to displayPreferences,
            "tabs" to storage.getTabs(),
            "sentenceRemovalList" to storage.getSentenceRemovalList(),
            "regexCleanupRules" to storage.getRegexRules(),
            "updateFollowedStoryIds" to storage.getUpdateFollowedStoryIds(),
            "ttsSettings" to storage.getTtsSettings(),
            "ttsSession" to storage.getTtsSession(),
            "foldLayoutMode" to displayPreferences.foldLayoutMode,
            "themeStorage" to mapOf("wa_theme_active_v1" to displayPreferences.activeThemeId),
        )
    }

    private fun storyWithoutLocalFiles(story: Story): Story =
        story.copy(
            epubPath = null,
            epubPaths = null,
            chapters =
                story.chapters
                    .map { it.copy(content = null, filePath = null, downloaded = false, downloadedAt = null) }
                    .toMutableList(),
        )

    private fun storyWithoutTransientPaths(story: Story): Story =
        story.copy(
            chapters = story.chapters.map { it.copy(filePath = null, content = null) }.toMutableList(),
            epubPath = null,
            epubPaths = null,
        )

    private fun collectChapterFiles(library: List<Story>): List<FullBackupChapterFile> =
        library.flatMap { story ->
            story.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.downloaded) return@mapIndexedNotNull null
                val source =
                    storage.resolveChapterPath(chapter.filePath)?.let(::File)?.takeIf(File::exists)
                        ?: return@mapIndexedNotNull null
                FullBackupChapterFile(
                    storyId = story.id,
                    chapterId = chapter.id,
                    chapterIndex = index,
                    title = chapter.title,
                    path = FullBackupPaths.chapterPath(story.id, chapter.id, index),
                    source = source,
                )
            }
        }

    /** Collects each story's trend-history file that actually exists on disk. Stories that have never
     *  been synced since the Trends feature shipped have no file and are skipped (restore recreates an
     *  empty history via the missing-file path). */
    private fun collectMetricFiles(library: List<Story>): List<FullBackupMetricFile> =
        library.mapNotNull { story ->
            val source = storage.metricFile(story.id).takeIf(File::exists) ?: return@mapNotNull null
            FullBackupMetricFile(storyId = story.id, path = FullBackupPaths.metricPath(story.id), source = source)
        }
}

private data class FullBackupChapterFile(
    val storyId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val title: String,
    val path: String,
    val source: File,
)

private data class FullBackupMetricFile(
    val storyId: String,
    val path: String,
    val source: File,
)
