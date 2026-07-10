package com.vinicius741.webnovelarchiver.data.storage

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.vinicius741.webnovelarchiver.cleanup.DefaultCleanup
import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
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
import com.vinicius741.webnovelarchiver.domain.story.StoryNormalization
import com.vinicius741.webnovelarchiver.feature.settings.PreferenceNormalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

/** Pre-import library/index snapshot used to roll back a failed JSON backup merge. */
internal data class JsonImportSnapshot(
    val index: File,
    val tabs: File,
    val storyDir: File,
    val hadIndex: Boolean,
    val hadTabs: Boolean,
    val preImportStoryFiles: Set<String>,
) {
    val root: File get() = index.parentFile ?: storyDir.parentFile ?: index
}

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
    internal val appVersion: String = appVersionOf(context),
) {
    internal val context: Context = context.applicationContext
    internal val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    internal val root = File(this.context.filesDir, "webnovel_archiver").apply { mkdirs() }
    internal val storyDir = File(root, "stories").apply { mkdirs() }
    internal val chapterRoot = File(root, "novels").apply { mkdirs() }
    internal val epubRoot = File(root, "epubs").apply { mkdirs() }
    internal val backupRoot = File(root, "backups").apply { mkdirs() }
    internal val restoreRoot = File(this.context.cacheDir, "webnovel_restore").apply { mkdirs() }

    internal val preRestoreSnapshotDir = File(this.context.cacheDir, "webnovel_restore_snapshot")

    internal val maintenanceCoordinator = MaintenanceCoordinator()
    val maintenanceState: StateFlow<MaintenanceState> = maintenanceCoordinator.state

    private val _storageHealth = MutableStateFlow(StorageHealthSnapshot())
    val storageHealth: StateFlow<StorageHealthSnapshot> = _storageHealth.asStateFlow()

    /** Backup/restore engine (Reliability R1.2 + R7). Delegated lazily to avoid constructing it for CRUD-only use. */
    private val backupRestore: BackupRestoreCoordinator by lazy { BackupRestoreCoordinator(this) }

    private val libraryIndex = File(root, "library_index.json")
    private val settingsFile = File(root, "settings.json")
    private val sourceSettingsFile = File(root, "source_download_settings.json")
    private val chapterFilterFile = File(root, "chapter_filter_settings.json")
    private val displayPreferencesFile = File(root, "display_preferences.json")
    private val tabsFile = File(root, "tabs.json")
    private val sentencesFile = File(root, "sentence_removal.json")
    private val regexFile = File(root, "regex_cleanup_rules.json")
    private val queueFile = File(root, "download_queue.json")
    private val updateFollowedStoriesFile = File(root, "update_followed_story_ids.json")
    private val ttsFile = File(root, "tts_settings.json")
    private val sessionFile = File(root, "tts_session.json")

    @Synchronized
    fun getLibrary(): MutableList<Story> {
        migrateLegacyAsyncStorageIfPresent()
        val ids = readLibraryIdsWithRecovery()
        return ids.mapNotNull { id -> readStory(storyFile(id)) }.toMutableList()
    }

    @Synchronized
    fun saveLibrary(stories: List<Story>) {
        readLibraryIdsWithRecovery()
        ensureHealthyForWrite(libraryIndex)
        stories.forEach { ensureHealthyForWrite(storyFile(it.id)) }
        stories.forEach { saveStoryOnly(it) }
        write(libraryIndex, stories.map { it.id })
    }

    @Synchronized
    fun getStory(id: String): Story? = readStory(storyFile(id))

    @Synchronized
    fun addOrUpdateStory(story: Story) {
        val ids = readLibraryIdsWithRecovery().toMutableList()
        ensureHealthyForWrite(libraryIndex)
        ensureHealthyForWrite(storyFile(story.id))
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
        val ids = readLibraryIdsWithRecovery().filterNot { it == id }
        ensureHealthyForWrite(libraryIndex)
        ensureHealthyForWrite(storyFile(id))
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
        // Health fences are process-local; wipe them so recreated same-named documents can write again.
        _storageHealth.value = StorageHealthSnapshot()
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

    fun getUpdateFollowedStoryIds(): MutableList<String> = read(updateFollowedStoriesFile) ?: mutableListOf()

    fun saveUpdateFollowedStoryIds(ids: List<String>) = write(updateFollowedStoriesFile, ids.filter { it.isNotBlank() }.distinct())

    fun getTtsSettings(): TtsSettings = PreferenceNormalization.ttsSettings(read(ttsFile) ?: TtsSettings())

    fun saveTtsSettings(settings: TtsSettings) = write(ttsFile, PreferenceNormalization.ttsSettings(settings))

    fun getTtsSession(): TtsSession? = read(sessionFile)

    fun saveTtsSession(session: TtsSession) = write(sessionFile, session)

    fun clearTtsSession() = maintenanceCoordinator.withStorageAccess(this) { sessionFile.delete() }

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
        val ids = readLibraryIdsWithRecovery()
        ids.forEach { id ->
            // Coerce Gson nulls + migrate paths; re-save when either changed so legacy documents
            // pick up new fields (e.g. publicationStatus) on disk once.
            val raw = read<Story>(storyFile(id)) ?: return@forEach
            val coerced = StoryNormalization.coerceDefaults(raw)
            val migrated = migrateChapterPaths(coerced.story)
            if (coerced.changed || migrated !== coerced.story) saveStoryOnly(migrated)
        }
    }

    @Synchronized
    fun saveQueue(jobs: List<DownloadJob>) = write(queueFile, jobs)

    /**
     * Snapshots the library index, every story file, and the tabs file into [dest], returning a
     * [JsonImportSnapshot] that [restoreJsonImportSnapshot] can use to roll a failed JSON import
     * back to its pre-import state. Intended for [BackupRestoreCoordinator.importBackupUri] (audit
     * gap 2): the JSON import path previously had no rollback, so a malformed or partial merge could
     * leave the library in a mixed state. The caller wraps read+merge+write in the same `storage`
     * monitor and only restores on failure.
     */
    @Synchronized
    internal fun snapshotLibraryAndTabs(dest: File): JsonImportSnapshot {
        check(!dest.exists() || dest.deleteRecursively()) { "Could not clear stale JSON import snapshot" }
        check(dest.mkdirs() || dest.isDirectory) { "Could not create JSON import snapshot" }
        val indexSnap = File(dest, "library_index.json")
        val tabsSnap = File(dest, "tabs.json")
        val storySnapDir = File(dest, "stories").apply { mkdirs() }
        var hadIndex = false
        if (libraryIndex.exists()) {
            libraryIndex.copyTo(indexSnap, overwrite = true)
            hadIndex = true
        }
        var hadTabs = false
        if (tabsFile.exists()) {
            tabsFile.copyTo(tabsSnap, overwrite = true)
            hadTabs = true
        }
        val storyFiles = storyDir.listFiles().orEmpty().filter(File::isFile)
        storyFiles.forEach { file ->
            file.copyTo(File(storySnapDir, file.name), overwrite = true)
        }
        return JsonImportSnapshot(
            index = indexSnap,
            tabs = tabsSnap,
            storyDir = storySnapDir,
            hadIndex = hadIndex,
            hadTabs = hadTabs,
            preImportStoryFiles = storyFiles.mapTo(linkedSetOf(), File::getName),
        )
    }

    /**
     * Restores the library index, story files, and tabs captured by [snapshotLibraryAndTabs] back
     * to their pre-import state. Only files that existed at snapshot time are restored; story files
     * created by the failed import are removed so the library does not retain orphaned entries.
     */
    @Synchronized
    internal fun restoreJsonImportSnapshot(snapshot: JsonImportSnapshot): Boolean {
        // Atomic replacement keeps the current live document intact if rollback copying fails.
        return runCatching {
            if (snapshot.hadIndex) {
                replaceAtomically(snapshot.index, libraryIndex)
            } else {
                check(!libraryIndex.exists() || libraryIndex.delete()) { "Could not remove imported library index" }
            }
            if (snapshot.hadTabs) {
                replaceAtomically(snapshot.tabs, tabsFile)
            } else {
                check(!tabsFile.exists() || tabsFile.delete()) { "Could not remove imported tabs" }
            }
            // Restore old artifacts before removing import-created files.
            snapshot.storyDir.listFiles().orEmpty().filter(File::isFile).forEach { snap ->
                replaceAtomically(snap, File(storyDir, snap.name))
            }
            storyDir.listFiles().orEmpty().filter(File::isFile).forEach { current ->
                if (current.name !in snapshot.preImportStoryFiles) {
                    check(current.delete()) { "Could not remove imported story file ${current.name}" }
                }
            }
        }.onFailure {
            Timber.e(it, "JSON import rollback failed; snapshot was preserved for recovery")
        }.isSuccess
    }

    /**
     * Copies [source] onto [target] without first deleting [target]: writes to a sibling temp file,
     * then renames it over [target] (a near-atomic replace on the same filesystem). The live [target]
     * is only clobbered once the new bytes are fully on disk, so a mid-copy failure preserves it.
     */
    private fun replaceAtomically(
        source: File,
        target: File,
    ) {
        AtomicFileWrites.stream(target) { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        check(target.exists()) { "Could not restore ${target.name}" }
    }

    /** Removes a temporary import snapshot directory once the import has committed successfully. */
    internal fun discardJsonImportSnapshot(snapshot: JsonImportSnapshot) {
        snapshot.root.deleteRecursively()
    }

    /**
     * Atomic read-modify-write of the queue: [transform] runs under the storage monitor so the read
     * and the write cannot be interleaved by another queue writer (e.g. the download process loop).
     * Non-suspending and fast (one small JSON file), so callers on the main thread don't block on a
     * coroutine mutex. [AppRepository] uses this same `storage` monitor for its multi-document RMW
     * (story + queue together) via `synchronized(storage)`, so this method, the repository, and the
     * download engine all share one serialization point — there is no separate transaction mutex.
     * (Audit gap 1: a previous version of this comment referenced a `txMutex` field that did not
     * exist; the JVM monitor on this [AppStorage] instance is the real lock.)
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

    @Synchronized
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

    @Synchronized
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

    @Synchronized
    fun readChapter(chapter: Chapter): String? {
        chapter.content?.let { return it }
        return resolveChapterPath(chapter.filePath)?.let { File(it).takeIf(File::exists)?.readText() }
    }

    /** Replaces an existing downloaded chapter while honoring portable relative storage paths. */
    @Synchronized
    fun overwriteChapter(
        chapter: Chapter,
        html: String,
    ): Boolean {
        val file = resolveChapterPath(chapter.filePath)?.let(::File)?.takeIf(File::exists) ?: return false
        AtomicFileWrites.writeText(file, html)
        return true
    }

    @Synchronized
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
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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

    // ──────────────────────────────────────────────────────────────────────────────
    // Backup / restore (Reliability R1.2 + R7). The full implementation lives in
    // [BackupRestoreCoordinator] so this class stays focused on JSON CRUD + path resolution.
    // These methods keep the original public surface so callers (SettingsScreen, MainActivity,
    // CleanupScreen) are unchanged.
    // ──────────────────────────────────────────────────────────────────────────────

    fun exportBackup(): File = backupRestore.exportBackup()

    fun exportCleanupRules(): File = backupRestore.exportCleanupRules()

    fun exportFullBackup(): File = backupRestore.exportFullBackup()

    fun importBackupUri(uri: Uri): String = backupRestore.importBackupUri(uri)

    fun importFullBackupUri(uri: Uri): String = backupRestore.importFullBackupUri(uri)

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

    /**
     * Resolve a chapter path that may be either a legacy absolute path or a new relative path under
     * [root]. [readChapter] and [BackupRestoreCoordinator.collectFullBackupChapterFiles] both route
     * through this.
     */
    internal fun resolveChapterPath(stored: String?): String? {
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

    /** Read + coerce Gson defaults + relative-path migration for a story document. */
    private fun readStory(file: File): Story? {
        val raw = read<Story>(file) ?: return null
        return migrateChapterPaths(StoryNormalization.coerceDefaults(raw).story)
    }

    internal fun relativize(file: File): String = file.toRelativeString(root)

    private inline fun <reified T> read(file: File): T? =
        maintenanceCoordinator.withStorageAccess(this) {
            when (val result = DurableJson.readAtomicResult<T>(file, gson)) {
                is DurableReadResult.Present -> {
                    // A successful decode means the document is readable again; drop sticky fences.
                    clearStorageIssues(file)
                    result.value
                }
                DurableReadResult.Absent -> null
                is DurableReadResult.Corrupt -> {
                    recordStorageIssue(
                        file,
                        StorageHealthKind.Corrupt,
                        "Document was quarantined after malformed JSON was detected",
                    )
                    null
                }
                is DurableReadResult.UnsupportedSchema -> {
                    recordStorageIssue(
                        file,
                        StorageHealthKind.UnsupportedSchema,
                        "Schema ${result.foundVersion} is not supported by schema ${result.supportedVersion}",
                    )
                    null
                }
                is DurableReadResult.IoFailure -> {
                    recordStorageIssue(file, StorageHealthKind.IoFailure, result.cause.message ?: "I/O failure")
                    null
                }
            }
        }

    private fun write(
        file: File,
        value: Any,
    ) {
        maintenanceCoordinator.withStorageAccess(this) {
            ensureHealthyForWrite(file)
            DurableJson.writeAtomic(file, gson, DurableJson.envelope(value, appVersion))
            clearStorageIssues(file)
        }
    }

    private fun ensureHealthyForWrite(file: File) {
        val blockedIssue =
            _storageHealth.value.issues.firstOrNull {
                it.document == file.name &&
                    it.kind in setOf(StorageHealthKind.UnsupportedSchema, StorageHealthKind.IoFailure)
            }
        check(blockedIssue == null) {
            "Refusing to overwrite unhealthy ${file.name}: ${blockedIssue?.detail}"
        }
    }

    @Synchronized
    private fun readLibraryIdsWithRecovery(): List<String> {
        val result = DurableJson.readAtomicResult<List<String>>(libraryIndex, gson)
        if (result is DurableReadResult.Present) {
            clearStorageIssues(libraryIndex)
            return result.value.filter { it.isNotBlank() }.distinct()
        }

        when (result) {
            is DurableReadResult.Corrupt ->
                recordStorageIssue(libraryIndex, StorageHealthKind.Corrupt, "Library index was corrupt and quarantined")
            is DurableReadResult.UnsupportedSchema ->
                recordStorageIssue(
                    libraryIndex,
                    StorageHealthKind.UnsupportedSchema,
                    "Library index schema ${result.foundVersion} is unsupported",
                )
            is DurableReadResult.IoFailure ->
                recordStorageIssue(libraryIndex, StorageHealthKind.IoFailure, result.cause.message ?: "I/O failure")
            DurableReadResult.Absent -> Unit
            is DurableReadResult.Present -> Unit
        }

        val storyFiles = storyDir.listFiles()?.toList().orEmpty()
        if (storyFiles.none { it.isFile && it.name.endsWith(".json") }) return emptyList()
        val recovery =
            LibraryIndexRecovery.scan(
                files = storyFiles,
                safeName = ::safeName,
                readStory = { file ->
                    DurableJson.readAtomicResult<Story>(file, gson, quarantineOnCorruption = false).also { storyResult ->
                        when (storyResult) {
                            is DurableReadResult.Corrupt ->
                                recordStorageIssue(file, StorageHealthKind.Corrupt, "Story document is corrupt and was left untouched")
                            is DurableReadResult.UnsupportedSchema ->
                                recordStorageIssue(file, StorageHealthKind.UnsupportedSchema, "Story schema is unsupported")
                            is DurableReadResult.IoFailure ->
                                recordStorageIssue(file, StorageHealthKind.IoFailure, storyResult.cause.message ?: "I/O failure")
                            is DurableReadResult.Present -> clearStorageIssues(file)
                            DurableReadResult.Absent -> Unit
                        }
                    }
                },
            )
        val recoveredIds = recovery.stories.map { it.id }
        // Persist a rebuilt index for recoverable cases so cold starts stop re-scanning. Leave an
        // UnsupportedSchema index untouched so a downgrade cannot clobber a newer on-disk shape.
        if (result !is DurableReadResult.UnsupportedSchema) {
            persistRecoveredLibraryIndex(recoveredIds)
        } else {
            recordStorageIssue(
                libraryIndex,
                StorageHealthKind.LibraryIndexRecovered,
                "Reconstructed an in-memory library index from valid story documents; unsupported index was not rewritten",
                recovery.stories.size,
            )
        }
        return recoveredIds
    }

    private fun persistRecoveredLibraryIndex(ids: List<String>) {
        // Drop sticky IoFailure/Corrupt fences for the index so intentional recovery can rewrite it.
        clearStorageIssues(libraryIndex)
        runCatching {
            maintenanceCoordinator.withStorageAccess(this) {
                DurableJson.writeAtomic(libraryIndex, gson, DurableJson.envelope(ids, appVersion))
            }
            recordStorageIssue(
                libraryIndex,
                StorageHealthKind.LibraryIndexRecovered,
                "Reconstructed and persisted library index from valid story documents",
                ids.size,
            )
        }.onFailure { error ->
            Timber.e(error, "Could not persist recovered library index")
            recordStorageIssue(
                libraryIndex,
                StorageHealthKind.LibraryIndexRecovered,
                "Reconstructed an in-memory library index from valid story documents; persistence failed",
                ids.size,
            )
            recordStorageIssue(libraryIndex, StorageHealthKind.IoFailure, error.message ?: "I/O failure")
        }
    }

    private fun recordStorageIssue(
        file: File,
        kind: StorageHealthKind,
        detail: String,
        recoveredStoryCount: Int = 0,
    ) {
        val issue = StorageHealthIssue(file.name, kind, detail, recoveredStoryCount)
        val retained = _storageHealth.value.issues.filterNot { it.document == issue.document && it.kind == issue.kind }
        _storageHealth.value = StorageHealthSnapshot(retained + issue)
    }

    private fun clearStorageIssues(file: File) {
        val retained = _storageHealth.value.issues.filterNot { it.document == file.name }
        if (retained.size != _storageHealth.value.issues.size) {
            _storageHealth.value = StorageHealthSnapshot(retained)
        }
    }

    internal fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun migrateLegacyAsyncStorageIfPresent() {
        if (libraryIndex.exists()) return
        val legacy = File(context.filesDir.parentFile, "databases/RKStorage")
        if (!legacy.exists()) return
        // Any old AsyncStorage-style SQLite layout is intentionally not parsed here; JSON backups remain compatible.
        write(libraryIndex, emptyList<String>())
    }

    companion object {
        fun appVersionOf(context: Context): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
    }
}
