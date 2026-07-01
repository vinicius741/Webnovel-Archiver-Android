package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.DefaultCleanup
import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.data.backup.BackupExportPlanning
import com.vinicius741.webnovelarchiver.data.backup.BackupMergePlanning
import com.vinicius741.webnovelarchiver.data.backup.FullBackupManifestValidation
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.backup.JsonBackupValidation
import com.vinicius741.webnovelarchiver.data.backup.RestoredChapterFileIndex
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveUtils
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.settings.PreferenceNormalization
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.google.gson.JsonParseException
import timber.log.Timber

/**
 * Low-level file-based persistence. All JSON documents are written through [DurableJson] (AtomicFile
 * with a schema/app-version envelope); chapter HTML and EPUBs go through [AtomicFileWrites]
 * (write-temp + fsync + rename). Chapter paths are stored relative to [root] so backups/restores
 * and app data moves stay portable. Full backup restore is transactional (staging root + atomic
 * swap), keeping the previous data intact until the new tree is fully written and verified.
 *
 * Reliability R1 (storage durability) + R1.3 (relative chapter paths) + R7 (hardened restore) live
 * here; higher-level transactional/state-flow APIs live in [AppRepository].
 */
class AppStorage(
    context: Context,
    /** Package version embedded into every durable JSON envelope for future migrations. */
    private val appVersion: String = appVersionOf(context),
) {
    private val context: Context = context.applicationContext
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val root = File(this.context.filesDir, "webnovel_archiver").apply { mkdirs() }
    private val storyDir = File(root, "stories").apply { mkdirs() }
    private val chapterRoot = File(root, "novels").apply { mkdirs() }
    private val epubRoot = File(root, "epubs").apply { mkdirs() }
    private val backupRoot = File(root, "backups").apply { mkdirs() }
    private val restoreRoot = File(this.context.cacheDir, "webnovel_restore").apply { mkdirs() }
    private val preRestoreSnapshotDir = File(this.context.cacheDir, "webnovel_restore_snapshot").apply { mkdirs() }

    private val libraryIndex = File(root, "library_index.json")
    private val settingsFile = File(root, "settings.json")
    private val sourceSettingsFile = File(root, "source_download_settings.json")
    private val chapterFilterFile = File(root, "chapter_filter_settings.json")
    private val displayPreferencesFile = File(root, "display_preferences.json")
    private val tabsFile = File(root, "tabs.json")
    private val sentencesFile = File(root, "sentence_removal.json")
    private val regexFile = File(root, "regex_cleanup_rules.json")
    private val queueFile = File(root, "download_queue.json")
    private val ttsFile = File(root, "tts_settings.json")
    private val sessionFile = File(root, "tts_session.json")

    @Synchronized
    fun getLibrary(): MutableList<Story> {
        migrateLegacyAsyncStorageIfPresent()
        val ids: List<String> = read(libraryIndex) ?: emptyList()
        return ids.mapNotNull { id -> read<Story>(storyFile(id))?.let(::migrateChapterPaths) }.toMutableList()
    }

    @Synchronized
    fun saveLibrary(stories: List<Story>) {
        stories.forEach { saveStoryOnly(it) }
        write(libraryIndex, stories.map { it.id })
    }

    @Synchronized
    fun getStory(id: String): Story? = read<Story>(storyFile(id))?.let(::migrateChapterPaths)

    @Synchronized
    fun addOrUpdateStory(story: Story) {
        val ids = (read<List<String>>(libraryIndex) ?: emptyList()).toMutableList()
        if (!ids.contains(story.id)) {
            ids.add(story.id)
            story.dateAdded = story.dateAdded ?: System.currentTimeMillis()
        } else {
            val existing = getStory(story.id)
            story.dateAdded = existing?.dateAdded ?: story.dateAdded
        }
        saveStoryOnly(story)
        write(libraryIndex, ids)
    }

    @Synchronized
    fun deleteStory(id: String) {
        val ids = (read<List<String>>(libraryIndex) ?: emptyList()).filterNot { it == id }
        write(libraryIndex, ids)
        storyFile(id).delete()
        File(chapterRoot, safeName(id)).deleteRecursively()
        File(epubRoot, safeName(id)).deleteRecursively()
        saveQueue(getQueue().filterNot { it.storyId == id })
    }

    @Synchronized
    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
        storyDir.mkdirs()
        chapterRoot.mkdirs()
        epubRoot.mkdirs()
        backupRoot.mkdirs()
    }

    fun getSettings(): AppSettings = PreferenceNormalization.appSettings(read(settingsFile) ?: AppSettings())

    fun saveSettings(settings: AppSettings) = write(settingsFile, PreferenceNormalization.appSettings(settings))

    fun getSourceDownloadSettings(): MutableMap<String, SourceDownloadSettings> =
        PreferenceNormalization.sourceDownloadSettings(read(sourceSettingsFile) ?: mutableMapOf())

    fun saveSourceDownloadSettings(settings: Map<String, SourceDownloadSettings>) =
        write(sourceSettingsFile, PreferenceNormalization.sourceDownloadSettings(settings))

    fun getChapterFilterSettings(): ChapterFilterSettings =
        PreferenceNormalization.chapterFilterSettings(read(chapterFilterFile) ?: ChapterFilterSettings())

    fun saveChapterFilterSettings(settings: ChapterFilterSettings) =
        write(chapterFilterFile, PreferenceNormalization.chapterFilterSettings(settings))

    fun getDisplayPreferences(): DisplayPreferences =
        PreferenceNormalization.displayPreferences(read(displayPreferencesFile) ?: DisplayPreferences())

    fun saveDisplayPreferences(preferences: DisplayPreferences) =
        write(displayPreferencesFile, PreferenceNormalization.displayPreferences(preferences))

    fun getTabs(): MutableList<Tab> = read(tabsFile) ?: mutableListOf()

    fun saveTabs(tabs: List<Tab>) = write(tabsFile, tabs.sortedBy { it.order })

    fun getSentenceRemovalList(): MutableList<String> = read(sentencesFile) ?: DefaultCleanup.sentences.toMutableList()

    fun saveSentenceRemovalList(items: List<String>) = write(sentencesFile, items)

    fun getRegexRules(): MutableList<RegexCleanupRule> = TextCleanup.sanitizeRegexRules(read(regexFile) ?: mutableListOf())

    fun saveRegexRules(rules: List<RegexCleanupRule>) = write(regexFile, TextCleanup.sanitizeRegexRules(rules))

    fun getTtsSettings(): TtsSettings = PreferenceNormalization.ttsSettings(read(ttsFile) ?: TtsSettings())

    fun saveTtsSettings(settings: TtsSettings) = write(ttsFile, PreferenceNormalization.ttsSettings(settings))

    fun getTtsSession(): TtsSession? = read(sessionFile)

    fun saveTtsSession(session: TtsSession) = write(sessionFile, session)

    fun clearTtsSession() = sessionFile.delete()

    @Synchronized
    fun getQueue(): MutableList<DownloadJob> = read(queueFile) ?: mutableListOf()

    /**
     * Recovers jobs left "downloading" by a killed process back to "pending". The whole
     * read-modify-write is one atomic, [Synchronized] operation: it reads and writes [queueFile]
     * directly instead of calling [getQueue]/[saveQueue], so a concurrent queue writer cannot
     * interleave between the read and the write and lose the recovery.
     */
    @Synchronized
    fun recoverInterruptedDownloads() {
        val jobs: MutableList<DownloadJob> = read(queueFile) ?: return
        var changed = false
        jobs.forEach { job ->
            if (job.status == DownloadJobStatus.Downloading.wire) {
                job.status = DownloadJobStatus.Pending.wire
                changed = true
            }
        }
        if (changed) write(queueFile, jobs)
    }

    /**
     * One-time on-disk migration of legacy absolute chapter/epub paths to paths relative to [root]
     * (R1.3). [migrateChapterPaths] converts paths on read but only persists them when something
     * else re-saves the story — so without this pass, every cold start would re-run the migration for
     * untouched stories forever. This walks the library once and re-saves any story that changed, so
     * the per-read migration converges to a no-op. Safe to call repeatedly: it writes only when a
     * story's paths actually changed.
     */
    @Synchronized
    fun migrateChapterPathsToRelative() {
        val ids: List<String> = read(libraryIndex) ?: return
        ids.forEach { id ->
            val raw = read<Story>(storyFile(id)) ?: return@forEach
            val migrated = migrateChapterPaths(raw)
            if (migrated !== raw) saveStoryOnly(migrated)
        }
    }

    @Synchronized
    fun saveQueue(jobs: List<DownloadJob>) = write(queueFile, jobs)

    /**
     * Atomic read-modify-write of the queue: [transform] runs under the storage monitor so the read
     * and the write cannot be interleaved by another queue writer (e.g. the download process loop).
     * Non-suspending and fast (one small JSON file), so callers on the main thread don't block on a
     * coroutine mutex the way the repository's [txMutex] path would. The repository's transactional
     * [txMutex] is still the owner for *multi-document* RMW (story + queue together); for the
     * single-document queue transforms the download engine needs, this monitor-atomic path is enough.
     */
    @Synchronized
    fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob> {
        val current = read<MutableList<DownloadJob>>(queueFile) ?: mutableListOf()
        val updated = transform(current)
        write(queueFile, updated)
        return updated
    }

    /**
     * Atomically persists an enqueued [story] (with its status/lastUpdated already mutated by the
     * caller) together with the new [jobs] queue, under one storage monitor acquisition. This is the
     * enqueue equivalent of [mutateQueueInPlace]: the story save + queue save can't be interleaved by
     * a concurrent writer, and no coroutine mutex blocking is involved (Reliability R3 — single-owner
     * without ANR risk from [runBlocking] on the main thread).
     */
    @Synchronized
    fun saveEnqueue(
        story: Story,
        jobs: List<DownloadJob>,
    ) {
        addOrUpdateStory(story)
        write(queueFile, jobs)
    }

    fun saveChapter(
        storyId: String,
        index: Int,
        chapter: Chapter,
        html: String,
    ): String {
        val file = chapterFile(storyId, index, chapter)
        AtomicFileWrites.writeText(file, html)
        return relativize(file)
    }

    fun copyChapterToStory(
        storyId: String,
        index: Int,
        chapter: Chapter,
    ): String? {
        val source = resolveChapterPath(chapter.filePath)?.let { File(it) }?.takeIf(File::exists) ?: return null
        val destination = chapterFile(storyId, index, chapter)
        source.copyTo(destination, overwrite = true)
        return relativize(destination)
    }

    fun readChapter(chapter: Chapter): String? {
        chapter.content?.let { return it }
        return resolveChapterPath(chapter.filePath)?.let { File(it).takeIf(File::exists)?.readText() }
    }

    /** Replaces an existing downloaded chapter while honoring portable relative storage paths. */
    fun overwriteChapter(
        chapter: Chapter,
        html: String,
    ): Boolean {
        val file = resolveChapterPath(chapter.filePath)?.let(::File)?.takeIf(File::exists) ?: return false
        AtomicFileWrites.writeText(file, html)
        return true
    }

    fun saveEpub(
        storyId: String,
        filename: String,
        bytes: ByteArray,
    ): File {
        val dir = File(epubRoot, safeName(storyId)).apply { mkdirs() }
        val file = File(dir, filename)
        AtomicFileWrites.writeBytes(file, bytes)
        return file
    }

    /**
     * Streams an EPUB to its final file (S5): [block] writes into an [OutputStream] backed by a temp
     * file, which is fsync'd and renamed into place on success. Lets EpubEngine stream the ZIP
     * chapter-by-chapter instead of building one big [ByteArrayOutputStream] in memory.
     */
    fun saveEpubStreamed(
        storyId: String,
        filename: String,
        block: (java.io.OutputStream) -> Unit,
    ): File {
        val dir = File(epubRoot, safeName(storyId)).apply { mkdirs() }
        val file = File(dir, filename)
        AtomicFileWrites.stream(file) { out -> block(out) }
        return file
    }

    /** Resolve a possibly-relative chapter/epub path to an absolute filesystem path. */
    fun resolveAbsolutePath(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val direct = File(path)
        if (direct.isAbsolute) return direct.takeIf(File::exists)
        return File(root, path).takeIf(File::exists)
    }

    /**
     * Lists every `.epub` file physically present for [storyId] under its per-story epub directory
     * (`webnovel_archiver/epubs/<safeName(storyId)>/`), oldest first. This is the source of truth for
     * the "Legacy EPUBs" screen: callers compare these absolute paths against [Story.epubPaths] to
     * separate currently-referenced files from orphans left behind by write-only regeneration (see
     * [com.vinicius741.webnovelarchiver.epub.EpubEngine.generate]). Returns an empty list when the
     * directory does not exist (a story that never generated an EPUB), never throws.
     */
    fun listEpubs(storyId: String): List<File> =
        File(epubRoot, safeName(storyId))
            .takeIf(File::exists)
            ?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }
            ?.sortedBy(File::lastModified)
            .orEmpty()

    /**
     * Deletes a single EPUB file for [storyId] located at [absolutePath]. Used by the Legacy EPUBs
     * screen to reclaim disk space from leftover files. Safety-checked at the storage boundary:
     * the target must be a real `.epub` file whose canonical parent is exactly this story's per-story
     * epub directory, which also defeats path-traversal (the canonical path must start with
     * [epubRoot]'s canonical path). Returns `true` only when the file was actually removed.
     */
    fun deleteEpubFile(
        storyId: String,
        absolutePath: String,
    ): Boolean {
        val file = File(absolutePath)
        if (!file.isFile || !file.name.endsWith(".epub", ignoreCase = true)) return false
        val storyEpubDir = File(epubRoot, safeName(storyId))
        // Canonicalize before comparing so `..`/symlink tricks can't escape the per-story directory.
        val canonicalParent = runCatching { file.parentFile?.canonicalPath }.getOrNull() ?: return false
        val canonicalRoot = runCatching { epubRoot.canonicalPath }.getOrNull() ?: return false
        val canonicalStoryDir = runCatching { storyEpubDir.canonicalPath }.getOrNull() ?: return false
        if (canonicalParent != canonicalStoryDir || !canonicalStoryDir.startsWith(canonicalRoot)) return false
        return file.delete()
    }

    fun exportBackup(): File {
        val sourceLibrary = getLibrary()
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
                "tabs" to getTabs(),
            )
        val json = gson.toJson(payload)
        BackupExportPlanning.validateJsonBackup(sourceLibrary.size, json.toByteArray().size.toLong())?.let { error(it) }
        val file = File(backupRoot, "webnovel_backup_${System.currentTimeMillis()}.json")
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
                "sentenceRemovalList" to getSentenceRemovalList(),
                "regexCleanupRules" to getRegexRules(),
            )
        val file = File(backupRoot, "webnovel_cleanup_rules_${System.currentTimeMillis()}.json")
        AtomicFileWrites.writeText(file, gson.toJson(payload))
        return file
    }

    fun exportFullBackup(): File {
        val file = File(backupRoot, "webnovel_full_backup_${System.currentTimeMillis()}.zip")
        val library = getLibrary()
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
                "settings" to getSettings(),
                "sourceDownloadSettings" to getSourceDownloadSettings(),
                "chapterFilterSettings" to getChapterFilterSettings(),
                "displayPreferences" to getDisplayPreferences(),
                "tabs" to getTabs(),
                "sentenceRemovalList" to getSentenceRemovalList(),
                "regexCleanupRules" to getRegexRules(),
                "ttsSettings" to getTtsSettings(),
                "ttsSession" to getTtsSession(),
                "foldLayoutMode" to getDisplayPreferences().foldLayoutMode,
                "themeStorage" to mapOf("wa_theme_active_v1" to getDisplayPreferences().activeThemeId),
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
        val existing = getLibrary()
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
        saveLibrary(existing)
        var importedTabs = 0
        (payload["tabs"] as? List<*>)?.let {
            val incomingTabs: MutableList<Tab> = gson.fromJson(gson.toJson(it), object : TypeToken<MutableList<Tab>>() {}.type)
            val currentTabs = getTabs()
            val existingIds = currentTabs.map { tab -> tab.id }.toMutableSet()
            incomingTabs.forEach { tab ->
                if (existingIds.add(tab.id)) {
                    currentTabs.add(tab)
                    importedTabs += 1
                }
            }
            currentTabs.forEachIndexed { index, tab -> currentTabs[index] = tab.copy(order = index) }
            saveTabs(currentTabs)
        }
        return "Imported ${added + updated} novels ($added new, $updated updated) and $importedTabs tabs"
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
            val restoreDir = File(restoreRoot, "${System.currentTimeMillis()}").apply { mkdirs() }
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
            val summary = FullBackupRestorePlanning.restoreSummary(stories)
            swapRoots(staged)
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
            val storyFile = File(stageStoryDir, "${safeName(story.id)}.json")
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
     * Snapshot the current [root] to the cache, then move [staged] into its place (P3 atomicity).
     *
     * The snapshot is kept until the swap is verified complete and is **only** deleted here on the
     * success path. On any failure the caller's catch block invokes [rollbackRootFromSnapshot] to
     * restore the previous data — so a half-completed cross-filesystem copy cannot leave a broken
     * library behind after what the user was told was a safe restore.
     */
    private fun swapRoots(staged: File) {
        preRestoreSnapshotDir.deleteRecursively()
        if (root.exists()) root.renameTo(preRestoreSnapshotDir)
        if (!staged.renameTo(root)) {
            // Cross-filesystem fallback: copy then delete. If this throws midway, the caller rolls
            // root back from preRestoreSnapshotDir; we never get here with the snapshot discarded.
            staged.copyRecursively(root, overwrite = true)
            staged.deleteRecursively()
        }
        root.mkdirs()
        storyDir.mkdirs()
        chapterRoot.mkdirs()
        epubRoot.mkdirs()
        backupRoot.mkdirs()
        // Restore succeeded; the snapshot can now be discarded.
        preRestoreSnapshotDir.deleteRecursively()
    }

    /**
     * P3: restore [root] from [preRestoreSnapshotDir] after a failed swap. If the snapshot exists,
     * the in-progress (possibly half-written) root is discarded and the previous good data is put
     * back, so a failed restore never leaves a broken library.
     */
    private fun rollbackRootFromSnapshot() {
        if (!preRestoreSnapshotDir.exists()) return
        runCatching {
            root.deleteRecursively()
            if (!preRestoreSnapshotDir.renameTo(root)) {
                // Cross-filesystem fallback for the rollback too.
                preRestoreSnapshotDir.copyRecursively(root, overwrite = true)
                preRestoreSnapshotDir.deleteRecursively()
            }
            root.mkdirs()
            storyDir.mkdirs()
            chapterRoot.mkdirs()
            epubRoot.mkdirs()
            backupRoot.mkdirs()
            Timber.w("Restored previous library from pre-restore snapshot after a failed swap.")
        }.onFailure { Timber.e(it, "Failed to roll back root from pre-restore snapshot; data may be inconsistent.") }
        preRestoreSnapshotDir.deleteRecursively()
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
            val file = File(stagedStoryDir, "${safeName(story.id)}.json")
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

    private fun saveStoryOnly(story: Story) {
        story.totalChapters = story.chapters.size
        story.downloadedChapters = story.chapters.count { it.downloaded }
        write(storyFile(story.id), story)
    }

    private fun storyFile(id: String) = File(storyDir, "${safeName(id)}.json")

    private fun chapterFile(
        storyId: String,
        index: Int,
        chapter: Chapter,
    ): File {
        val dir = File(chapterRoot, safeName(storyId)).apply { mkdirs() }
        val filename = "${index.toString().padStart(4, '0')}_${safeName(chapter.title).ifBlank { chapter.id }}.html"
        return File(dir, filename)
    }

    private fun collectFullBackupChapterFiles(library: List<Story>): List<FullBackupChapterFile> {
        return library.flatMap { story ->
            story.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.downloaded) return@mapIndexedNotNull null
                val source = resolveChapterPath(chapter.filePath)?.let { File(it) }?.takeIf(File::exists) ?: return@mapIndexedNotNull null
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

    /**
     * Resolve a chapter path that may be either a legacy absolute path or a new relative path under
     * [root]. [readChapter] and [collectFullBackupChapterFiles] both route through this.
     */
    private fun resolveChapterPath(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        val direct = File(stored)
        if (direct.isAbsolute) return stored
        val resolved = File(root, stored)
        return resolved.takeIf(File::exists)?.absolutePath
    }

    /**
     * On read, convert any legacy absolute chapter/epub path into a path relative to [root] so the
     * on-disk model becomes portable. Non-existent absolute paths are left untouched (they may live
     * under a different install's filesDir and will be re-derived on next download).
     */
    private fun migrateChapterPaths(story: Story): Story {
        var changed = false
        val chapters =
            story.chapters
                .map { chapter ->
                    val absolute = chapter.filePath ?: return@map chapter
                    val file = File(absolute)
                    if (file.isAbsolute && file.startsWith(root)) {
                        changed = true
                        chapter.copy(filePath = relativize(file))
                    } else {
                        chapter
                    }
                }.toMutableList()
        val epubPaths =
            story.epubPaths
                ?.mapNotNull { path ->
                    val file = File(path)
                    if (file.isAbsolute && file.startsWith(root)) {
                        changed = true
                        relativize(file)
                    } else {
                        path
                    }
                }?.toMutableList()
        val epubPath =
            story.epubPath?.let { path ->
                val file = File(path)
                if (file.isAbsolute && file.startsWith(root)) {
                    changed = true
                    relativize(file)
                } else {
                    path
                }
            }
        return if (!changed) story else story.copy(chapters = chapters, epubPaths = epubPaths, epubPath = epubPath)
    }

    private fun relativize(file: File): String = file.toRelativeString(root)

    private inline fun <reified T> read(file: File): T? = DurableJson.readAtomic<T>(file, gson)

    private fun write(
        file: File,
        value: Any,
    ) {
        DurableJson.writeAtomic(file, gson, DurableJson.envelope(value, appVersion))
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun migrateLegacyAsyncStorageIfPresent() {
        if (libraryIndex.exists()) return
        val legacy = File(context.filesDir.parentFile, "databases/RKStorage")
        if (!legacy.exists()) return
        // React Native AsyncStorage's SQLite layout is intentionally not parsed here; JSON backups remain compatible.
        write(libraryIndex, emptyList<String>())
    }

    companion object {
        fun appVersionOf(context: Context): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
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
