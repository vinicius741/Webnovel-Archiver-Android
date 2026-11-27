package com.vinicius741.webnovelarchiver.data.storage

import android.net.Uri
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.backup.QuickBackupFormat
import com.vinicius741.webnovelarchiver.data.backup.QuickBackupMergePlanning
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipException

/**
 * Merges a quick backup into the live library (`QuickBackupMergePlanning`): metadata and settings
 * come from the backup, chapter text and other files on this phone are never deleted or replaced.
 * Unlike a full restore there is no root swap, because `novels/` is never touched. Every file the
 * merge writes is copied aside first and put back if a later write fails.
 */
internal class QuickBackupRestorer(
    private val storage: AppStorage,
) {
    private val gson get() = storage.gson

    fun import(uri: Uri): String {
        val staging = File(storage.restoreRoot, "quick_${System.currentTimeMillis()}")
        return try {
            check(!staging.exists() || staging.deleteRecursively()) { "Could not clear the restore folder" }
            val entries =
                storage.context.contentResolver
                    .openInputStream(uri)
                    ?.use { QuickBackupZip.read(it, File(staging, "raw")) }
                    ?: return storage.context.getString(R.string.error_no_file_selected)
            restore(entries, File(staging, "raw"))
        } catch (error: IOException) {
            Timber.e(error, "Could not read quick backup")
            failure(error)
        } catch (error: IllegalStateException) {
            failure(error)
        } catch (error: JsonParseException) {
            failure(error)
        } finally {
            if (staging.exists() && !staging.deleteRecursively()) Timber.w("Could not remove quick restore staging")
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun restore(
        entries: QuickBackupEntries,
        raw: File,
    ): String {
        val payload: Map<String, Any?> =
            gson.fromJson(entries.manifestText, object : TypeToken<Map<String, Any?>>() {}.type)
                ?: error("Invalid quick backup: the manifest is empty")
        val manifest = QuickBackupFormat.parseValidated(gson, payload)
        val indexed = (manifest.metricFiles.map { it.path } + manifest.coverFiles.map { it.path }).toSet()
        check(entries.files == indexed) { "Invalid quick backup: the files do not match its index" }

        val plan =
            QuickBackupMergePlanning.mergeLibrary(
                backup = manifest.library,
                local = storage.getLibrary(),
                hasLocalText = storage::chapterAvailable,
                fileExists = { path -> storage.resolveAbsolutePath(path)?.isFile == true },
                backupCovers = manifest.coverFiles.associate { it.storyId to it.path },
            )
        val config = mergedConfig(manifest.config)
        val metrics = manifest.metricFiles.filter { newerHistory(File(raw, it.path), it.storyId) }
        // Durable app files, not the cache: after a failed rollback this is the only copy of the previous files.
        val journal = FileJournal(File(storage.context.filesDir, "webnovel_quick_restore_previous_${System.currentTimeMillis()}"))
        try {
            plan.stories.forEach { journal.keep(File(storage.storyDir, "${storage.safeName(it.id)}.json")) }
            journal.keep(storage.libraryIndex)
            storage.saveLibrary(plan.stories)
            plan.coversToWrite.forEach { (_, path) ->
                val target = File(storage.root, path)
                journal.keep(target)
                AtomicFileWrites.writeAtomically(target) { out -> File(raw, path).inputStream().use { it.copyTo(out) } }
            }
            metrics.forEach { entry ->
                val target = storage.metricFile(entry.storyId)
                journal.keep(target)
                AtomicFileWrites.writeAtomically(target) { out -> File(raw, entry.path).inputStream().use { it.copyTo(out) } }
            }
            CONFIG_FILES.forEach { journal.keep(File(storage.root, it)) }
            RestoreStagingWriter(storage).writeConfig(storage.root, config)
            journal.discard()
        } catch (error: Throwable) {
            if (error is CancellationException || error is VirtualMachineError || error is InterruptedException) throw error
            Timber.e(error, "Quick restore failed; putting the previous files back")
            if (!journal.rollBack()) return ROLLBACK_FAILED
            journal.discard()
            throw IllegalStateException(error.message ?: "a file could not be written", error)
        }
        return QuickBackupMergePlanning.summary(plan.counts)
    }

    /** The backup's settings, with its tabs and listening positions merged into this phone's. */
    private fun mergedConfig(backup: Map<String, Any>): Map<String, Any> {
        val config = backup.toMutableMap()
        val tabs = backup["tabs"]?.let { gson.fromJson<List<Tab>>(gson.toJson(it), object : TypeToken<List<Tab>>() {}.type) }
        config["tabs"] = QuickBackupMergePlanning.mergeTabs(tabs, storage.tabsDoc.get())
        val positions =
            backup["ttsPositions"]?.let {
                gson.fromJson<Map<String, TtsStoryPosition>>(gson.toJson(it), object : TypeToken<Map<String, TtsStoryPosition>>() {}.type)
            }
        config["ttsPositions"] = QuickBackupMergePlanning.mergeTtsPositions(positions, storage.getTtsStoryPositions())
        return config
    }

    private fun newerHistory(
        file: File,
        storyId: String,
    ): Boolean {
        val decoded = DurableJson.decodeText<StoryMetricHistory>(file.readText(), gson)
        val backup = (decoded as? DurableReadResult.Present)?.value ?: error("Invalid quick backup: unreadable trend history")
        return QuickBackupMergePlanning.backupHistoryIsNewer(backup, storage.getMetricHistory(storyId))
    }

    private fun failure(error: Throwable): String {
        val detail = if (error is ZipException) "the file is not a valid backup" else error.message ?: "unknown error"
        return "${FullBackupRestorePlanning.RESTORE_FAILED_PREFIX}: $detail. Your library was not changed."
    }

    /** Copies each file aside before its first write so a failed restore can put every one back. */
    private class FileJournal(
        private val directory: File,
    ) {
        private val kept = LinkedHashMap<File, File?>()

        fun keep(target: File) {
            if (target in kept) return
            val copy =
                target.takeIf(File::isFile)?.let {
                    File(directory, kept.size.toString()).also { copy ->
                        it.copyTo(copy, overwrite = true)
                    }
                }
            kept[target] = copy
        }

        fun rollBack(): Boolean =
            runCatching {
                kept.forEach { (target, copy) ->
                    if (copy == null) {
                        check(!target.exists() || target.delete()) { "Could not remove ${target.name}" }
                    } else {
                        AtomicFileWrites.writeAtomically(target) { out -> copy.inputStream().use { it.copyTo(out) } }
                    }
                }
            }.onFailure { Timber.e(it, "Quick restore rollback failed; the copies stay in %s", directory.name) }.isSuccess

        fun discard() {
            if (directory.exists() && !directory.deleteRecursively()) Timber.w("Could not remove %s", directory.name)
        }
    }

    private companion object {
        /** Every document `RestoreStagingWriter.writeConfig` may write. */
        val CONFIG_FILES =
            listOf(
                "settings.json",
                "source_download_settings.json",
                "chapter_filter_settings.json",
                "display_preferences.json",
                "tabs.json",
                "sentence_removal.json",
                "regex_cleanup_rules.json",
                "tts_settings.json",
                "tts_session.json",
                "tts_positions.json",
            )

        const val ROLLBACK_FAILED = "Restore failed and the previous files could not all be put back. Recovery data was preserved."
    }
}
