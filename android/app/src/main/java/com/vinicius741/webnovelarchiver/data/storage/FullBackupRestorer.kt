package com.vinicius741.webnovelarchiver.data.storage

import android.net.Uri
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.data.backup.FullBackupManifestValidation
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.backup.RestoredChapterFileIndex
import com.vinicius741.webnovelarchiver.data.backup.RestoredMetricFileIndex
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipException

internal class FullBackupRestorer(
    private val storage: AppStorage,
) {
    private val context get() = storage.context
    private val gson get() = storage.gson
    private val stagingWriter = RestoreStagingWriter(storage)
    private val committer = RestoreRootCommitter(storage)

    fun import(uri: Uri): String {
        val temp =
            RestoreFailureMessages.createInputFile(context.cacheDir)
                ?: return RestoreFailureMessages.storage("could not create temporary file")
        return try {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return context.getString(R.string.error_no_file_selected)
            input.use { source ->
                temp.outputStream().use { sink ->
                    BackupInputLimits.copyWithLimit(source, sink, BackupInputLimits.MAX_ZIP_INPUT_BYTES, "Full backup")
                }
            }
            val restoreDir = File(storage.restoreRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
            restoreFromZip(temp, restoreDir)
        } catch (error: IOException) {
            Timber.e(error, "Could not read full-backup input")
            RestoreFailureMessages.storage(error.message ?: "I/O error")
        } catch (error: IllegalStateException) {
            "Restore failed: ${error.message ?: "the backup was rejected"}. Your library was not changed."
        } finally {
            temp.delete()
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun restoreFromZip(
        zipFile: File,
        restoreDir: File,
    ): String {
        var swapCandidate: File? = null
        return try {
            val raw = freshDirectory(restoreDir, "raw_extraction")
            val staged = freshDirectory(restoreDir, "staged_root")
            val extracted = FullBackupZipExtractor.extract(zipFile, raw, restoreDir.usableSpace)
            val payload = readAndValidateManifest(raw)
            val chapterFiles = chapterFileIndex(payload)
            val metricFiles = metricFileIndex(payload)
            verifyZipIndex(extracted.files, chapterFiles, metricFiles)
            val stories = buildStagedRoot(raw, staged, payload, chapterFiles)
            verifyStagedTree(staged, stories)?.let { return it }
            swapCandidate = committer.stageBesideLiveRoot(staged)
            synchronized(storage) { committer.commit(checkNotNull(swapCandidate)) }
            FullBackupRestorePlanning.restoreSummary(stories)
        } catch (error: Throwable) {
            val rollbackSucceeded = committer.rollback()
            rethrowFatal(error)
            Timber.e(error, "Restore failed")
            RestoreFailureMessages.from(error, rollbackSucceeded = rollbackSucceeded)
        } finally {
            cleanup(restoreDir)
            swapCandidate?.takeIf(File::exists)?.let(::cleanup)
        }
    }

    private fun readAndValidateManifest(raw: File): Map<String, Any> {
        val manifest = File(raw, "manifest.json")
        check(manifest.exists()) { FullBackupManifestValidation.MISSING_MANIFEST_MESSAGE }
        val text =
            manifest.inputStream().use {
                BackupInputLimits.readUtf8(it, BackupInputLimits.MAX_MANIFEST_BYTES, "Full-backup manifest")
            }
        val payload: Map<String, Any> =
            gson.fromJson(text, object : TypeToken<Map<String, Any>>() {}.type)
                ?: error("Invalid full backup: manifest was empty")
        FullBackupManifestValidation.validate(payload)?.let { error(it) }
        return payload
    }

    private fun chapterFileIndex(payload: Map<String, Any>): List<RestoredChapterFileIndex> {
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val entries: List<Map<String, Any?>> = gson.fromJson(gson.toJson(payload["chapterFiles"]), type)
        return entries.map { entry ->
            RestoredChapterFileIndex(
                storyId = entry["storyId"]?.toString().orEmpty(),
                chapterId = entry["chapterId"]?.toString().orEmpty(),
                path = entry["path"]?.toString().orEmpty(),
            )
        }
    }

    private fun metricFileIndex(payload: Map<String, Any>): List<RestoredMetricFileIndex> {
        // metricFiles is optional (backups predating the Trends feature omit it). A missing or null
        // entry yields an empty index — no metric entries to verify or stage.
        val raw = payload["metricFiles"] ?: return emptyList()
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val entries: List<Map<String, Any?>> = gson.fromJson(gson.toJson(raw), type) ?: return emptyList()
        return entries.map { entry ->
            RestoredMetricFileIndex(
                storyId = entry["storyId"]?.toString().orEmpty(),
                path = entry["path"]?.toString().orEmpty(),
            )
        }
    }

    private fun verifyZipIndex(
        extractedFiles: Set<String>,
        chapterFiles: List<RestoredChapterFileIndex>,
        metricFiles: List<RestoredMetricFileIndex>,
    ) {
        val expected =
            chapterFiles.mapTo(mutableSetOf()) { it.path }.apply {
                add("manifest.json")
                metricFiles.forEach { add(it.path) }
            }
        check(extractedFiles == expected) {
            val unexpected = extractedFiles - expected
            val missing = expected - extractedFiles
            when {
                unexpected.isNotEmpty() -> "Invalid full backup: unindexed ZIP entry ${unexpected.first()}"
                missing.isNotEmpty() -> "Invalid full backup: missing ZIP entry ${missing.first()}"
                else -> "Invalid full backup: ZIP index mismatch"
            }
        }
    }

    private fun buildStagedRoot(
        raw: File,
        staged: File,
        payload: Map<String, Any>,
        chapterFiles: List<RestoredChapterFileIndex>,
    ): MutableList<Story> {
        val stories: MutableList<Story> =
            gson.fromJson(gson.toJson(payload["library"]), object : TypeToken<MutableList<Story>>() {}.type)
        FullBackupRestorePlanning.scrubTransientState(stories)
        val config: Map<String, Any> =
            payload["config"]?.let {
                gson.fromJson(gson.toJson(it), object : TypeToken<Map<String, Any>>() {}.type)
            } ?: payload
        File(raw, "novels").takeIf(File::exists)?.copyRecursively(File(staged, "novels").apply { mkdirs() }, overwrite = true)
        // Trend history files extract under raw/metrics/<encoded>.json; copy the whole tree verbatim
        // so restore preserves per-novel score/Patreon history. The commit step moves the entire
        // staged root into place, and initializeStorageDirectories() recreates metrics/ if absent.
        File(raw, "metrics").takeIf(File::exists)?.copyRecursively(File(staged, "metrics").apply { mkdirs() }, overwrite = true)
        File(staged, "epubs").mkdirs()
        stagingWriter.writeConfig(staged, config)
        FullBackupRestorePlanning.applyRestoredChapterFiles(stories, chapterFiles) { path ->
            File(staged, path).takeIf(File::exists)?.toRelativeString(staged)
        }
        stagingWriter.writeLibrary(staged, stories)
        return stories
    }

    private fun verifyStagedTree(
        staged: File,
        stories: List<Story>,
    ): String? {
        val storyDirectory = File(staged, "stories")
        stories.forEach { story ->
            val storyFile = File(storyDirectory, "${storage.safeName(story.id)}.json")
            if (!storyFile.exists()) return "Restore verify failed: missing story file for ${story.id}"
            val reparsed =
                runCatching { gson.fromJson<Story>(storyFile.readText(), Story::class.java) }.getOrNull()
                    ?: return "Restore verify failed: story ${story.id} did not parse"
            reparsed.chapters.filter { it.downloaded }.forEach { chapter ->
                val path = chapter.filePath
                if (path.isNullOrBlank() || !File(staged, path).isFile) {
                    return "Restore verify failed: chapter file missing for ${story.id}/${chapter.id}"
                }
            }
        }
        return null
    }

    private fun freshDirectory(
        parent: File,
        name: String,
    ): File =
        File(parent, name).apply {
            check(!exists() || deleteRecursively()) { "Could not clear restore staging directory $name" }
            check(mkdirs() || isDirectory) { "Could not create restore staging directory $name" }
        }

    private fun cleanup(file: File) {
        if (file.exists() && !file.deleteRecursively()) Timber.w("Could not remove restore artifact %s", file.name)
    }

    private fun rethrowFatal(error: Throwable) {
        if (error is InterruptedException || error is CancellationException || error is VirtualMachineError) throw error
    }
}

private object RestoreFailureMessages {
    fun createInputFile(cacheDirectory: File): File? =
        try {
            File.createTempFile("webnovel_restore", ".zip", cacheDirectory)
        } catch (error: IOException) {
            Timber.e(error, "Could not create full-backup input staging file")
            null
        }

    fun from(
        error: Throwable,
        rollbackSucceeded: Boolean = true,
    ): String {
        if (!rollbackSucceeded) {
            return "Restore failed and automatic rollback could not be verified. Recovery data was preserved."
        }
        return when (error) {
            is ZipException ->
                "Restore failed: the backup file is corrupt or not a valid backup (${error.message ?: "invalid ZIP"}). " +
                    "Your library was not changed."
            is IOException -> storage(error.message ?: "I/O error")
            is JsonParseException ->
                "Restore failed: the backup contents could not be read " +
                    "(${error.message ?: "invalid JSON"}). Your library was not changed."
            else -> "Restore failed: ${error.message ?: "unknown error"}. Your library was not changed."
        }
    }

    fun storage(detail: String): String = "Restore failed: a storage error occurred ($detail). Your library was not changed."
}
