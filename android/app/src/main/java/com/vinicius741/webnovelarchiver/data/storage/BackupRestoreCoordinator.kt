package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.data.backup.BackupExportPlanning
import com.vinicius741.webnovelarchiver.data.backup.BackupMergePlanning
import com.vinicius741.webnovelarchiver.data.backup.FullBackupManifestValidation
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.backup.JsonBackupValidation
import com.vinicius741.webnovelarchiver.data.backup.RestoredChapterFileIndex
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.settings.PreferenceNormalization
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Transactional backup/restore engine (Reliability R1.2 + R7), extracted from [AppStorage] so the
 * storage class stays focused on JSON CRUD + path resolution.
 *
 * Three export formats (a JSON-library snapshot, a cleanup-rules-only export, and a full ZIP archive
 * that carries settings + every downloaded chapter file) and two import paths (JSON merge, full
 * transactional restore). Full-restore is the load-bearing path: the existing data root is
 * snapshotted to cache, the backup is extracted into a *staging* root, validated, and only then
 * swapped in as the live root; on any failure the snapshot is restored so the user's previous
 * library survives (P3). The pure planning/validation helpers under `data/backup` (the
 * `...Planning.kt` and `...Validation.kt` siblings) remain the source of truth for the deterministic parts.
 *
 * Reliability comments (R1.2 / R7 / P3 / S5 / E3 / T1) document cross-cutting invariants and are
 * preserved verbatim from the original [AppStorage] implementation.
 */
class BackupRestoreCoordinator(
    private val storage: AppStorage,
) {
    private val context: Context get() = storage.context
    private val gson: Gson get() = storage.gson
    private val appVersion: String get() = storage.appVersion

    fun exportBackup(): File {
        val sourceLibrary = storage.getLibrary()
        val library =
            sourceLibrary.map { story ->
                story.copy(
                    epubPath = null,
                    epubPaths = null,
                    chapters =
                        story.chapters
                            .map { chapter ->
                                chapter.copy(
                                    content = null,
                                    filePath = null,
                                    downloaded = false,
                                )
                            }.toMutableList(),
                )
            }
        val payload =
            mapOf(
                "version" to 2,
                "exportDate" to
                    java.time.Instant
                        .now()
                        .toString(),
                "library" to library,
                "tabs" to storage.getTabs(),
            )
        val json = gson.toJson(payload)
        BackupExportPlanning.validateJsonBackup(sourceLibrary.size, json.toByteArray().size.toLong())?.let { error(it) }
        val file = File(storage.backupRoot, "webnovel_backup_${System.currentTimeMillis()}.json")
        AtomicFileWrites.writeText(file, json)
        return file
    }

    fun exportCleanupRules(): File {
        val payload =
            mapOf(
                "version" to 1,
                "exportDate" to
                    java.time.Instant
                        .now()
                        .toString(),
                "sentenceRemovalList" to storage.getSentenceRemovalList(),
                "regexCleanupRules" to storage.getRegexRules(),
            )
        val file = File(storage.backupRoot, "webnovel_cleanup_rules_${System.currentTimeMillis()}.json")
        AtomicFileWrites.writeText(file, gson.toJson(payload))
        return file
    }

    fun exportFullBackup(): File {
        val file = File(storage.backupRoot, "webnovel_full_backup_${System.currentTimeMillis()}.zip")
        val library = storage.getLibrary()
        BackupExportPlanning.validateFullBackup(library.size)?.let { error(it) }
        val chapterFiles = collectFullBackupChapterFiles(library)
        val manifestLibrary =
            library.map { story ->
                story.copy(
                    chapters = story.chapters.map { chapter -> chapter.copy(filePath = null, content = null) }.toMutableList(),
                    epubPath = null,
                    epubPaths = null,
                )
            }
        val config =
            mapOf(
                "settings" to storage.getSettings(),
                "sourceDownloadSettings" to storage.getSourceDownloadSettings(),
                "chapterFilterSettings" to storage.getChapterFilterSettings(),
                "displayPreferences" to storage.getDisplayPreferences(),
                "tabs" to storage.getTabs(),
                "sentenceRemovalList" to storage.getSentenceRemovalList(),
                "regexCleanupRules" to storage.getRegexRules(),
                "updateFollowedStoryIds" to storage.getUpdateFollowedStoryIds(),
                "ttsSettings" to storage.getTtsSettings(),
                "ttsSession" to storage.getTtsSession(),
                "foldLayoutMode" to storage.getDisplayPreferences().foldLayoutMode,
                "themeStorage" to mapOf("wa_theme_active_v1" to storage.getDisplayPreferences().activeThemeId),
            )
        // Stream the ZIP straight to a temp file, then rename into place (S5 + R1 durability).
        AtomicFileWrites.stream(file) { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    gson
                        .toJson(
                            mapOf(
                                "format" to "webnovel-archiver-full-backup",
                                "version" to 1,
                                "exportDate" to
                                    java.time.Instant
                                        .now()
                                        .toString(),
                                "library" to manifestLibrary,
                                "config" to config,
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
                            ),
                        ).toByteArray(),
                )
                zip.closeEntry()
                chapterFiles.forEach { chapterFile ->
                    zip.putNextEntry(ZipEntry(chapterFile.path))
                    chapterFile.source.inputStream().copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // E3: rollback dispatches by exception type; CancellationException + VM Errors re-throw after rollback.
    fun importBackupUri(uri: Uri): String {
        val text =
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return context.getString(R.string.error_no_file_selected)
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val payload =
            runCatching { gson.fromJson<Map<String, Any?>>(text, type) }.getOrNull()
                ?: return "Invalid backup file: not valid JSON"
        JsonBackupValidation.validate(payload)?.let { return it }
        val storiesJson = gson.toJson(payload["library"])
        val stories: MutableList<Story> =
            runCatching {
                gson.fromJson<MutableList<Story>>(storiesJson, object : TypeToken<MutableList<Story>>() {}.type)
            }.getOrNull() ?: return "Invalid backup file: malformed story data"

        // Audit gap 2 / Rec 3: the JSON import previously ran getLibrary → merge → saveLibrary →
        // getTabs → saveTabs with each @Synchronized call serializing independently, so a concurrent
        // writer (download engine, sync, repository) could interleave between the read and the write
        // and its changes would be lost — and a failure mid-save could leave a half-written library.
        // The whole snapshot + read-merge-write + rollback/commit cleanup now runs under one
        // `storage` monitor acquisition (the same lock AppRepository/DownloadEngine use), so no
        // concurrent writer can land between the snapshot and import or between a failure and rollback.
        return synchronized(storage) {
            val snapshotDir = File(storage.restoreRoot, "json_import_${System.currentTimeMillis()}")
            val snapshot = storage.snapshotLibraryAndTabs(snapshotDir)
            try {
                val existing = storage.getLibrary()
                var added = 0
                var updated = 0
                stories.forEach { incoming ->
                    val index = existing.indexOfFirst { it.id == incoming.id }
                    if (index >= 0) {
                        existing[index] = BackupMergePlanning.mergeJsonBackupStory(incoming, existing[index])
                        updated++
                    } else {
                        existing.add(BackupMergePlanning.mergeJsonBackupStory(incoming, null))
                        added++
                    }
                }
                storage.saveLibrary(existing)
                var importedTabs = 0
                (payload["tabs"] as? List<*>)?.let {
                    val incomingTabs: MutableList<Tab> = gson.fromJson(gson.toJson(it), object : TypeToken<MutableList<Tab>>() {}.type)
                    val currentTabs = storage.getTabs()
                    val existingIds = currentTabs.map { tab -> tab.id }.toMutableSet()
                    incomingTabs.forEach { tab ->
                        if (existingIds.add(tab.id)) {
                            currentTabs.add(tab)
                            importedTabs += 1
                        }
                    }
                    currentTabs.forEachIndexed { index, tab -> currentTabs[index] = tab.copy(order = index) }
                    storage.saveTabs(currentTabs)
                }
                // Commit: the import succeeded, so the pre-import snapshot is no longer needed.
                storage.discardJsonImportSnapshot(snapshot)
                "Imported ${added + updated} novels ($added new, $updated updated) and $importedTabs tabs"
            } catch (error: Throwable) {
                // Roll back to the pre-import library so a malformed/partial merge never leaves a mixed
                // state, then discard the snapshot dir on every path (otherwise each failed import leaks
                // a full library copy under restoreRoot — there is no startup reaper for json_import_*).
                // CancellationException/VM errors propagate after rollback (see restoreFromZip).
                runCatching { storage.restoreJsonImportSnapshot(snapshot) }
                runCatching { storage.discardJsonImportSnapshot(snapshot) }
                if (error is InterruptedException || error is kotlinx.coroutines.CancellationException || error is VirtualMachineError) {
                    throw error
                }
                Timber.e(error, "JSON backup import failed; library rolled back to pre-import state")
                "Import failed: ${error.message ?: "invalid backup"}. Your library was not changed."
            }
        }
    }

    /**
     * Transactional full-restore (R1.2 + R7). The existing data root is snapshotted to cache, the
     * backup is extracted into a *staging* root, validated, and only then swapped in as the live
     * [root]. On any failure the snapshot is restored so the user's previous library survives.
     */
    fun importFullBackupUri(uri: Uri): String {
        val temp = File.createTempFile("webnovel_restore", ".zip", context.cacheDir)
        try {
            // Step 1: copy the backup stream fully into [temp] and close both streams before we
            // touch [temp] again. (The previous version ran restoreFromZip *inside* the output
            // stream's `.use {}`, so restore read [temp] while it was still being written.)
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return context.getString(R.string.error_no_file_selected)
            input.use { source -> temp.outputStream().use { sink -> source.copyTo(sink) } }
            // Step 2: extract + validate + stage + swap, reading [temp] only after the copy closed.
            val restoreDir = File(storage.restoreRoot, "${System.currentTimeMillis()}").apply { mkdirs() }
            return restoreFromZip(temp, restoreDir)
        } finally {
            temp.delete()
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // E3: dispatches by exception type (Zip/IO/JSON); CancellationException + VM Errors are re-thrown above.
    private fun restoreFromZip(
        zipFile: File,
        restoreDir: File,
    ): String {
        val staged =
            File(restoreDir, "staged_root").apply {
                deleteRecursively()
                mkdirs()
            }
        try {
            val stageChapterRoot = File(staged, "novels").apply { mkdirs() }
            val stageStoryDir = File(staged, "stories").apply { mkdirs() }

            // 1. Extract into cache, with per-entry and total-size limits (R7 hardening).
            extractZipWithLimits(zipFile, restoreDir)
            val manifestFile = File(restoreDir, "manifest.json")
            if (!manifestFile.exists()) return FullBackupManifestValidation.MISSING_MANIFEST_MESSAGE
            val payload = gson.fromJson(manifestFile.readText(), object : TypeToken<Map<String, Any>>() {}.type) as Map<String, Any>
            FullBackupManifestValidation.validate(payload)?.let { return it }

            val stories: MutableList<Story> =
                gson.fromJson(
                    gson.toJson(payload["library"]),
                    object : TypeToken<MutableList<Story>>() {}.type,
                )
            FullBackupRestorePlanning.scrubTransientState(stories)
            val configPayload: Map<String, Any> =
                payload["config"]?.let {
                    gson.fromJson(gson.toJson(it), object : TypeToken<Map<String, Any>>() {}.type)
                } ?: payload

            // 2. Build a complete replacement tree under [staged]: chapter files, then config, then stories.
            File(restoreDir, "novels").takeIf(File::exists)?.copyRecursively(stageChapterRoot, overwrite = true)
            File(staged, "epubs").mkdirs() // EPUBs are regenerated, not restored.
            writeConfigTo(staged, configPayload)
            // Rewrite chapter paths as relative under the staged root.
            FullBackupRestorePlanning.applyRestoredChapterFiles(stories, chapterFileIndex(payload)) { backupPath ->
                File(staged, backupPath).takeIf(File::exists)?.let { it.toRelativeString(staged) }
            }
            writeLibraryTo(staged, stageStoryDir, stories)

            // 3. Verify staged tree: every story JSON parses + referenced chapter files exist.
            val verifyError = verifyStagedTree(staged, stories)
            if (verifyError != null) return verifyError

            // 4. Atomic swap: snapshot current root, replace with staged, keep snapshot until success.
            // Rec 3 / audit gap: hold the shared `storage` monitor across the *rename* so a concurrent
            // writer (download engine, sync, repository transaction) cannot land a JSON write against
            // the old root just as it is being renamed away. The slow cross-filesystem copy (when
            // staged and root live on different filesystems) runs BEFORE the monitor so it does not
            // block every @Synchronized storage call (download loop, UI getLibrary/getQueue) for the
            // whole copy — only the cheap renames are serialized.
            val summary = FullBackupRestorePlanning.restoreSummary(stories)
            val swapSource = stageForSwap(staged)
            synchronized(storage) {
                swapRoots(swapSource)
            }
            cleanupRestoreArtifacts(restoreDir)
            return summary
        } catch (error: Throwable) {
            // E3: a scope/thread cancellation or a serious VM Error (OOM on a huge ZIP, etc.) must
            // propagate — it is not a recoverable restore failure, and swallowing it would mask the
            // problem and run cleanup side-effects on an already-tearing-down context.
            if (error is InterruptedException || error is kotlinx.coroutines.CancellationException) {
                rollbackRootFromSnapshot()
                cleanupRestoreArtifacts(restoreDir)
                throw error
            }
            if (error is VirtualMachineError) {
                rollbackRootFromSnapshot()
                cleanupRestoreArtifacts(restoreDir)
                throw error
            }
            // P3: if the swap threw midway, root may be a half-populated tree. Restore the pre-swap
            // snapshot so the user keeps their previous library instead of a broken one.
            rollbackRootFromSnapshot()
            cleanupRestoreArtifacts(restoreDir)
            // E3: log the real cause (T1) — previously every failure was flattened to one toast with
            // no record of whether it was a corrupt ZIP, disk-full, or a malformed manifest.
            Timber.e(error, "Restore failed")
            return when (error) {
                is ZipException -> "Restore failed: the backup file is corrupt or not a valid backup (${error.message ?: "invalid ZIP"})."
                is IOException -> "Restore failed: a storage error occurred (${error.message ?: "I/O error"}). Your library was not changed."
                is JsonParseException -> "Restore failed: the backup contents could not be read (${error.message ?: "invalid JSON"}). Your library was not changed."
                is IllegalStateException -> "Restore failed: ${error.message ?: "the backup was rejected"}. Your library was not changed."
                else -> "Restore failed: ${error.message ?: "unknown error"}. Your library was not changed."
            }
        }
    }

    private fun extractZipWithLimits(
        zipFile: File,
        restoreDir: File,
    ): Int {
        var entries = 0
        var totalUncompressed = 0L
        val maxEntries = 200_000
        val maxTotalBytes = 2_000_000_000L // 2 GB safety cap
        val maxPerEntry = 100_000_000L // 100 MB per chapter/epub
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > maxEntries) error("Backup has too many entries (>$maxEntries)")
                val out =
                    ArchiveUtils.safeExtractionTarget(restoreDir, entry.name)
                        ?: error("Invalid full backup: unsafe ZIP entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    var written = 0L
                    out.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            totalUncompressed += read
                            if (written > maxPerEntry) error("Backup entry too large: ${entry.name}")
                            if (totalUncompressed > maxTotalBytes) error("Backup uncompressed size exceeds limit")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        return entries
    }

    private fun chapterFileIndex(payload: Map<String, Any>): List<RestoredChapterFileIndex> {
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val chapterFiles: List<Map<String, Any?>> =
            runCatching {
                gson.fromJson<List<Map<String, Any?>>>(gson.toJson(payload["chapterFiles"]), type)
            }.getOrNull() ?: emptyList()
        return chapterFiles.map {
            RestoredChapterFileIndex(
                storyId = it["storyId"]?.toString().orEmpty(),
                chapterId = it["chapterId"]?.toString().orEmpty(),
                path = it["path"]?.toString().orEmpty(),
            )
        }
    }

    private fun verifyStagedTree(
        staged: File,
        stories: List<Story>,
    ): String? {
        val stageStoryDir = File(staged, "stories")
        stories.forEach { story ->
            val storyFile = File(stageStoryDir, "${storage.safeName(story.id)}.json")
            if (!storyFile.exists()) return "Restore verify failed: missing story file for ${story.id}"
            val reparsed =
                runCatching { gson.fromJson<Story>(storyFile.readText(), Story::class.java) }.getOrNull()
                    ?: return "Restore verify failed: story ${story.id} did not parse"
            reparsed.chapters.filter { it.downloaded }.forEach { chapter ->
                val relative = chapter.filePath
                if (relative.isNullOrBlank() || !File(staged, relative).exists()) {
                    return "Restore verify failed: chapter file missing for ${story.id}/${chapter.id}"
                }
            }
        }
        return null
    }

    /**
     * Snapshot the current [root] to the cache, then move [source] into its place (P3 atomicity).
     *
     * [source] is expected to already sit on the same filesystem as [root] (see [stageForSwap]) so the
     * replacement is a cheap rename; any slow cross-filesystem copy was done before the monitor was
     * taken. The snapshot is kept until the swap is verified complete and is **only** deleted here on
     * the success path. On any failure the caller's catch block invokes [rollbackRootFromSnapshot] to
     * restore the previous data — so a half-completed copy cannot leave a broken library behind after
     * what the user was told was a safe restore.
     *
     * When [source] is the [stageForSwap] colocated temp it is consumed by the rename; the original
     * staged dir is cleaned by [cleanupRestoreArtifacts] in the caller.
     */
    private fun swapRoots(source: File) {
        storage.preRestoreSnapshotDir.deleteRecursively()
        if (storage.root.exists()) storage.root.renameTo(storage.preRestoreSnapshotDir)
        if (!source.renameTo(storage.root)) {
            // Defensive fallback: source was staged alongside root so this should always be a same-FS
            // rename, but if it still fails, copy then delete. If this throws midway, the caller rolls
            // root back from preRestoreSnapshotDir; we never get here with the snapshot discarded.
            source.copyRecursively(storage.root, overwrite = true)
            source.deleteRecursively()
        }
        storage.root.mkdirs()
        storage.storyDir.mkdirs()
        storage.chapterRoot.mkdirs()
        storage.epubRoot.mkdirs()
        storage.backupRoot.mkdirs()
        // Restore succeeded; the snapshot can now be discarded.
        storage.preRestoreSnapshotDir.deleteRecursively()
    }

    /**
     * If [staged] is on a different filesystem than [root], copy it to a temp directory alongside
     * [root] (same filesystem) so [swapRoots] can do a cheap rename under the monitor instead of a
     * slow cross-filesystem copy. Returns [staged] itself when the two are already co-located (no
     * copy needed). The caller passes the result to [swapRoots] and lets it clean up the temp.
     *
     * Runs OUTSIDE the storage monitor so the heavy copy does not block other storage callers.
     */
    private fun stageForSwap(staged: File): File {
        val root = storage.root
        if (sameFilesystem(staged, root)) return staged
        // Co-locate with root so the later rename is same-FS and near-atomic.
        val colocated = File(root.parentFile, "webnovel_restore_swap_${System.currentTimeMillis()}")
        var completed = false
        try {
            staged.copyRecursively(colocated, overwrite = true)
            completed = true
            return colocated
        } finally {
            // If the copy threw midway, delete the partial colocated tree so it isn't orphaned; the
            // exception propagates and the caller rolls back from the pre-restore snapshot.
            if (!completed) colocated.deleteRecursively()
        }
    }

    /** True when [a] and [b] share a parent directory on the same filesystem (rename is then atomic). */
    private fun sameFilesystem(a: File, b: File): Boolean {
        // Canonicalize to resolve symlinks (e.g. emulated storage), then compare parent paths. This is
        // a heuristic: when it wrongly says "same", swapRoots' renameTo-fallback still handles the rare
        // real cross-FS case. It just decides whether to pre-copy; correctness is not at stake.
        val ap = runCatching { a.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return true
        val bp = runCatching { b.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return true
        return ap == bp
    }

    /**
     * P3: restore [root] from [preRestoreSnapshotDir] after a failed swap. If the snapshot exists,
     * the in-progress (possibly half-written) root is discarded and the previous good data is put
     * back, so a failed restore never leaves a broken library.
     */
    private fun rollbackRootFromSnapshot() {
        if (!storage.preRestoreSnapshotDir.exists()) return
        runCatching {
            storage.root.deleteRecursively()
            if (!storage.preRestoreSnapshotDir.renameTo(storage.root)) {
                // Cross-filesystem fallback for the rollback too.
                storage.preRestoreSnapshotDir.copyRecursively(storage.root, overwrite = true)
                storage.preRestoreSnapshotDir.deleteRecursively()
            }
            storage.root.mkdirs()
            storage.storyDir.mkdirs()
            storage.chapterRoot.mkdirs()
            storage.epubRoot.mkdirs()
            storage.backupRoot.mkdirs()
            Timber.w("Restored previous library from pre-restore snapshot after a failed swap.")
        }.onFailure { Timber.e(it, "Failed to roll back root from pre-restore snapshot; data may be inconsistent.") }
        storage.preRestoreSnapshotDir.deleteRecursively()
    }

    private fun cleanupRestoreArtifacts(restoreDir: File) {
        restoreDir.deleteRecursively()
    }

    private fun writeLibraryTo(
        stagedRoot: File,
        stagedStoryDir: File,
        stories: List<Story>,
    ) {
        stagedStoryDir.mkdirs()
        writeStagedEnvelope(File(stagedRoot, "library_index.json"), stories.map { it.id })
        stories.forEach { story ->
            story.totalChapters = story.chapters.size
            story.downloadedChapters = story.chapters.count { it.downloaded }
            val file = File(stagedStoryDir, "${storage.safeName(story.id)}.json")
            writeStagedEnvelope(file, story)
        }
    }

    /** Writes each restored config document directly into [stagedRoot] via the same envelope shape. */
    private fun writeConfigTo(
        stagedRoot: File,
        payload: Map<String, Any>,
    ) {
        payload["settings"]?.let {
            writeStagedEnvelope(
                File(stagedRoot, "settings.json"),
                PreferenceNormalization.appSettings(normalizeConfig(it, AppSettings::class.java)),
            )
        }
        payload["sourceDownloadSettings"]?.let {
            val sourceSettings =
                normalizeConfig<Map<String, SourceDownloadSettings>>(
                    it,
                    object : TypeToken<MutableMap<String, SourceDownloadSettings>>() {}.type,
                )
            writeStagedEnvelope(
                File(stagedRoot, "source_download_settings.json"),
                PreferenceNormalization.sourceDownloadSettings(sourceSettings),
            )
        }
        payload["chapterFilterSettings"]?.let {
            writeStagedEnvelope(
                File(stagedRoot, "chapter_filter_settings.json"),
                PreferenceNormalization.chapterFilterSettings(normalizeConfig(it, ChapterFilterSettings::class.java)),
            )
        }
        payload["displayPreferences"]?.let {
            writeStagedEnvelope(
                File(stagedRoot, "display_preferences.json"),
                PreferenceNormalization.displayPreferences(normalizeConfig(it, DisplayPreferences::class.java)),
            )
        }
        payload["tabs"]?.let {
            val tabs = normalizeConfig<List<Tab>>(it, object : TypeToken<MutableList<Tab>>() {}.type)
            writeStagedEnvelope(File(stagedRoot, "tabs.json"), tabs.sortedBy { it.order })
        }
        payload["sentenceRemovalList"]?.let {
            val sentences = normalizeConfig<List<String>>(it, object : TypeToken<MutableList<String>>() {}.type)
            writeStagedEnvelope(File(stagedRoot, "sentence_removal.json"), sentences)
        }
        payload["regexCleanupRules"]?.let {
            val rules = normalizeConfig<List<RegexCleanupRule>>(it, object : TypeToken<MutableList<RegexCleanupRule>>() {}.type)
            writeStagedEnvelope(File(stagedRoot, "regex_cleanup_rules.json"), TextCleanup.sanitizeRegexRules(rules))
        }
        payload["updateFollowedStoryIds"]?.let {
            val ids = normalizeConfig<List<String>>(it, object : TypeToken<MutableList<String>>() {}.type)
            writeStagedEnvelope(File(stagedRoot, "update_followed_story_ids.json"), ids.filter { id -> id.isNotBlank() }.distinct())
        }
        payload["ttsSettings"]?.let {
            writeStagedEnvelope(
                File(stagedRoot, "tts_settings.json"),
                PreferenceNormalization.ttsSettings(normalizeConfig(it, TtsSettings::class.java)),
            )
        }
        payload["ttsSession"]?.let {
            writeStagedEnvelope(File(stagedRoot, "tts_session.json"), normalizeConfig(it, TtsSession::class.java))
        }
    }

    private fun writeStagedEnvelope(
        file: File,
        value: Any,
    ) {
        AtomicFileWrites.writeText(file, gson.toJson(DurableJson.envelope(value, appVersion)))
    }

    private fun <T> normalizeConfig(
        payload: Any,
        type: java.lang.reflect.Type,
    ): T = gson.fromJson(gson.toJson(payload), type)

    private fun collectFullBackupChapterFiles(library: List<Story>): List<FullBackupChapterFile> =
        library.flatMap { story ->
            story.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.downloaded) return@mapIndexedNotNull null
                val source =
                    storage.resolveChapterPath(chapter.filePath)?.let { File(it) }?.takeIf(File::exists) ?: return@mapIndexedNotNull null
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
}

private data class FullBackupChapterFile(
    val storyId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val title: String,
    val path: String,
    val source: File,
)
