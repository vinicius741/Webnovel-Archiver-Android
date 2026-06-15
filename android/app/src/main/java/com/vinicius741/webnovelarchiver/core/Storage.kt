package com.vinicius741.webnovelarchiver.core

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppStorage(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val root = File(context.filesDir, "webnovel_archiver").apply { mkdirs() }
    private val storyDir = File(root, "stories").apply { mkdirs() }
    private val chapterRoot = File(root, "novels").apply { mkdirs() }
    private val epubRoot = File(root, "epubs").apply { mkdirs() }
    private val backupRoot = File(root, "backups").apply { mkdirs() }

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
        return ids.mapNotNull { id -> read<Story>(storyFile(id)) }.toMutableList()
    }

    @Synchronized
    fun saveLibrary(stories: List<Story>) {
        stories.forEach { saveStoryOnly(it) }
        write(libraryIndex, stories.map { it.id })
    }

    @Synchronized
    fun getStory(id: String): Story? = read(storyFile(id))

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
    fun getRegexRules(): MutableList<RegexCleanupRule> =
        TextCleanup.sanitizeRegexRules(read(regexFile) ?: mutableListOf())
    fun saveRegexRules(rules: List<RegexCleanupRule>) = write(regexFile, TextCleanup.sanitizeRegexRules(rules))
    fun getTtsSettings(): TtsSettings = PreferenceNormalization.ttsSettings(read(ttsFile) ?: TtsSettings())
    fun saveTtsSettings(settings: TtsSettings) = write(ttsFile, PreferenceNormalization.ttsSettings(settings))
    fun getTtsSession(): TtsSession? = read(sessionFile)
    fun saveTtsSession(session: TtsSession) = write(sessionFile, session)
    fun clearTtsSession() = sessionFile.delete()
    fun getQueue(): MutableList<DownloadJob> = read(queueFile) ?: mutableListOf()

    fun recoverInterruptedDownloads() {
        val jobs = getQueue()
        var changed = false
        jobs.forEach { job ->
            if (job.status == "downloading") {
                job.status = "pending"
                changed = true
            }
        }
        if (changed) saveQueue(jobs)
    }
    fun saveQueue(jobs: List<DownloadJob>) = write(queueFile, jobs)

    fun saveChapter(storyId: String, index: Int, chapter: Chapter, html: String): String {
        val dir = File(chapterRoot, safeName(storyId)).apply { mkdirs() }
        val filename = "${index.toString().padStart(4, '0')}_${safeName(chapter.title).ifBlank { chapter.id }}.html"
        val file = File(dir, filename)
        file.writeText(html)
        return file.absolutePath
    }

    fun copyChapterToStory(storyId: String, index: Int, chapter: Chapter): String? {
        val source = chapter.filePath?.let { File(it) }?.takeIf(File::exists) ?: return null
        val dir = File(chapterRoot, safeName(storyId)).apply { mkdirs() }
        val filename = "${index.toString().padStart(4, '0')}_${safeName(chapter.title).ifBlank { chapter.id }}.html"
        val destination = File(dir, filename)
        source.copyTo(destination, overwrite = true)
        return destination.absolutePath
    }

    fun readChapter(chapter: Chapter): String? {
        chapter.content?.let { return it }
        return chapter.filePath?.let { File(it).takeIf(File::exists)?.readText() }
    }

    fun saveEpub(storyId: String, filename: String, bytes: ByteArray): File {
        val dir = File(epubRoot, safeName(storyId)).apply { mkdirs() }
        val file = File(dir, filename)
        file.writeBytes(bytes)
        return file
    }

    fun exportBackup(): File {
        val sourceLibrary = getLibrary()
        val library = sourceLibrary.map { story ->
            story.copy(
                epubPath = null,
                epubPaths = null,
                chapters = story.chapters.map { chapter ->
                    chapter.copy(
                        content = null,
                        filePath = null,
                        downloaded = false,
                    )
                }.toMutableList(),
            )
        }
        val payload = mapOf(
            "version" to 2,
            "exportDate" to java.time.Instant.now().toString(),
            "library" to library,
            "tabs" to getTabs(),
        )
        val json = gson.toJson(payload)
        BackupExportPlanning.validateJsonBackup(sourceLibrary.size, json.toByteArray().size.toLong())?.let { error(it) }
        val file = File(backupRoot, "webnovel_backup_${System.currentTimeMillis()}.json")
        file.writeText(json)
        return file
    }

    fun exportCleanupRules(): File {
        val payload = mapOf(
            "version" to 1,
            "exportDate" to java.time.Instant.now().toString(),
            "sentenceRemovalList" to getSentenceRemovalList(),
            "regexCleanupRules" to getRegexRules(),
        )
        val file = File(backupRoot, "webnovel_cleanup_rules_${System.currentTimeMillis()}.json")
        file.writeText(gson.toJson(payload))
        return file
    }

    fun exportFullBackup(): File {
        val file = File(backupRoot, "webnovel_full_backup_${System.currentTimeMillis()}.zip")
        val library = getLibrary()
        BackupExportPlanning.validateFullBackup(library.size)?.let { error(it) }
        val chapterFiles = collectFullBackupChapterFiles(library)
        val manifestLibrary = library.map { story ->
            story.copy(
                chapters = story.chapters.map { chapter -> chapter.copy(filePath = null, content = null) }.toMutableList(),
                epubPath = null,
                epubPaths = null,
            )
        }
        val config = mapOf(
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
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(gson.toJson(mapOf(
                "format" to "webnovel-archiver-full-backup",
                "version" to 1,
                "exportDate" to java.time.Instant.now().toString(),
                "library" to manifestLibrary,
                "config" to config,
                "chapterFiles" to chapterFiles.map {
                    mapOf(
                        "storyId" to it.storyId,
                        "chapterId" to it.chapterId,
                        "chapterIndex" to it.chapterIndex,
                        "title" to it.title,
                        "path" to it.path,
                    )
                },
            )).toByteArray())
            zip.closeEntry()
            chapterFiles.forEach { chapterFile ->
                zip.putNextEntry(ZipEntry(chapterFile.path))
                chapterFile.source.inputStream().copyTo(zip)
                zip.closeEntry()
            }
        }
        return file
    }

    fun importBackupUri(uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return "No file selected"
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val payload = runCatching { gson.fromJson<Map<String, Any?>>(text, type) }.getOrNull()
            ?: return "Invalid backup file: not valid JSON"
        JsonBackupValidation.validate(payload)?.let { return it }
        val storiesJson = gson.toJson(payload["library"])
        val stories: MutableList<Story> = runCatching {
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
            currentTabs.forEachIndexed { index, tab -> tab.order = index }
            saveTabs(currentTabs)
        }
        return "Imported ${added + updated} novels ($added new, $updated updated) and $importedTabs tabs"
    }

    fun importFullBackupUri(uri: Uri): String {
        val temp = File.createTempFile("webnovel_restore", ".zip", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
        val restoreDir = File(context.cacheDir, "webnovel_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        ZipInputStream(temp.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = ArchiveUtils.safeExtractionTarget(restoreDir, entry.name)
                    ?: return "Invalid full backup: unsafe ZIP entry"
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val manifest = File(restoreDir, "manifest.json")
        if (!manifest.exists()) return FullBackupManifestValidation.MISSING_MANIFEST_MESSAGE
        val payload = gson.fromJson(manifest.readText(), object : TypeToken<Map<String, Any>>() {}.type) as Map<String, Any>
        FullBackupManifestValidation.validate(payload)?.let { return it }
        clearAll()
        val stories: MutableList<Story> = gson.fromJson(gson.toJson(payload["library"]), object : TypeToken<MutableList<Story>>() {}.type)
        FullBackupRestorePlanning.scrubTransientState(stories)
        val configPayload: Map<String, Any> = payload["config"]?.let {
            gson.fromJson(gson.toJson(it), object : TypeToken<Map<String, Any>>() {}.type)
        } ?: payload
        restoreFullBackupConfig(configPayload)
        File(restoreDir, "novels").takeIf { it.exists() }?.copyRecursively(chapterRoot, overwrite = true)
        restoreChapterFilePaths(stories, payload["chapterFiles"])
        saveLibrary(stories)
        return FullBackupRestorePlanning.restoreSummary(stories)
    }

    private fun restoreFullBackupConfig(payload: Map<String, Any>) {
        payload["settings"]?.let {
            saveSettings(gson.fromJson(gson.toJson(it), AppSettings::class.java))
        }
        payload["tabs"]?.let {
            val tabs: MutableList<Tab> = gson.fromJson(gson.toJson(it), object : TypeToken<MutableList<Tab>>() {}.type)
            saveTabs(tabs)
        }
        payload["sourceDownloadSettings"]?.let {
            val sourceSettings: MutableMap<String, SourceDownloadSettings> =
                gson.fromJson(gson.toJson(it), object : TypeToken<MutableMap<String, SourceDownloadSettings>>() {}.type)
            saveSourceDownloadSettings(sourceSettings)
        }
        payload["chapterFilterSettings"]?.let {
            saveChapterFilterSettings(gson.fromJson(gson.toJson(it), ChapterFilterSettings::class.java))
        }
        payload["displayPreferences"]?.let {
            saveDisplayPreferences(gson.fromJson(gson.toJson(it), DisplayPreferences::class.java))
        }
        payload["sentenceRemovalList"]?.let {
            val sentences: MutableList<String> = gson.fromJson(gson.toJson(it), object : TypeToken<MutableList<String>>() {}.type)
            saveSentenceRemovalList(sentences)
        }
        payload["regexCleanupRules"]?.let {
            val rules: MutableList<RegexCleanupRule> = gson.fromJson(gson.toJson(it), object : TypeToken<MutableList<RegexCleanupRule>>() {}.type)
            saveRegexRules(rules)
        }
        payload["ttsSettings"]?.let {
            saveTtsSettings(gson.fromJson(gson.toJson(it), TtsSettings::class.java))
        }
        payload["ttsSession"]?.let {
            saveTtsSession(gson.fromJson(gson.toJson(it), TtsSession::class.java))
        }
    }

    private fun saveStoryOnly(story: Story) {
        story.totalChapters = story.chapters.size
        story.downloadedChapters = story.chapters.count { it.downloaded }
        write(storyFile(story.id), story)
    }

    private fun storyFile(id: String) = File(storyDir, "${safeName(id)}.json")

    private fun collectFullBackupChapterFiles(library: List<Story>): List<FullBackupChapterFile> {
        return library.flatMap { story ->
            story.chapters.mapIndexedNotNull { index, chapter ->
                if (!chapter.downloaded) return@mapIndexedNotNull null
                val source = chapter.filePath?.let { File(it) }?.takeIf(File::exists) ?: return@mapIndexedNotNull null
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

    private fun restoreChapterFilePaths(stories: MutableList<Story>, chapterFilesPayload: Any?) {
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val chapterFiles: List<Map<String, Any?>> = runCatching {
            gson.fromJson<List<Map<String, Any?>>>(gson.toJson(chapterFilesPayload), type)
        }.getOrNull() ?: emptyList()
        val index = chapterFiles.map {
            RestoredChapterFileIndex(
                storyId = it["storyId"]?.toString().orEmpty(),
                chapterId = it["chapterId"]?.toString().orEmpty(),
                path = it["path"]?.toString().orEmpty(),
            )
        }
        FullBackupRestorePlanning.applyRestoredChapterFiles(stories, index) { backupPath ->
            File(root, backupPath).takeIf(File::exists)?.absolutePath
        }
    }

    private inline fun <reified T> read(file: File): T? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson<T>(file.readText(), object : TypeToken<T>() {}.type) }.getOrNull()
    }

    private fun write(file: File, value: Any) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(value))
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun migrateLegacyAsyncStorageIfPresent() {
        if (libraryIndex.exists()) return
        val legacy = File(context.filesDir.parentFile, "databases/RKStorage")
        if (!legacy.exists()) return
        // React Native AsyncStorage's SQLite layout is intentionally not parsed here; JSON backups remain compatible.
        write(libraryIndex, emptyList<String>())
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
