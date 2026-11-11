package com.vinicius741.webnovelarchiver.data.storage

import android.net.Uri
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.data.backup.FullBackupRestorePlanning
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.BackupChapterRecoveryPlanning
import com.vinicius741.webnovelarchiver.domain.story.LegacyArchiveFoldPlanning
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.time.Instant

/** Outcome of [BackupChapterRecovery.recover]: a one-line [message] plus per-novel [details]. */
data class BackupChapterRecoveryResult(
    val message: String,
    val details: List<String> = emptyList(),
    val succeeded: Boolean = true,
)

/**
 * Merges downloaded chapters from a full-backup ZIP into the live library
 * ([BackupChapterRecoveryPlanning]) instead of replacing it the way a restore does. The ZIP is
 * validated with the restore's own checks, extracted to a cache folder, and each novel is handled
 * on its own: adopted files are copied into the novel's folder, every readable backup chapter is
 * verified to be readable in the result, and only then is the novel written. A failure leaves that
 * novel exactly as it was.
 */
internal class BackupChapterRecovery(
    private val storage: AppStorage,
) {
    private val context get() = storage.context
    private val manifestReader by lazy { FullBackupRestorer(storage) }

    @Suppress("TooGenericExceptionCaught") // Every failure becomes a message; the library stays as it was.
    fun recover(
        uri: Uri,
        keyFor: (Story) -> (Chapter) -> String,
    ): BackupChapterRecoveryResult {
        val workDir = File(storage.restoreRoot, "recover_${System.currentTimeMillis()}")
        ActivityLog.record(ActivityCategory.RECOVERY, "recovery_started")
        return try {
            check(workDir.mkdirs() || workDir.isDirectory) { "could not create a work folder" }
            val zip = File(workDir, "input.zip")
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return BackupChapterRecoveryResult(context.getString(R.string.error_no_file_selected), succeeded = false)
            input.use { source ->
                zip.outputStream().use { sink ->
                    BackupInputLimits.copyWithLimit(source, sink, BackupInputLimits.MAX_ZIP_INPUT_BYTES, "Full backup")
                }
            }
            val raw = File(workDir, "raw").apply { mkdirs() }
            val extracted =
                FullBackupZipExtractor.extract(
                    zip,
                    raw,
                    workDir.usableSpace,
                    budgetBytes = BackupInputLimits.recoveryExtractionBudget(workDir.usableSpace),
                )
            zip.delete()
            val manifest = manifestReader.readAndValidateManifest(raw)
            manifestReader.verifyZipIndex(
                extracted.files,
                manifest.chapterFiles,
                manifest.metricFiles,
                manifest.coverFiles,
                manifest.rewriteFiles,
            )
            val backupStories = manifest.library.toMutableList()
            FullBackupRestorePlanning.scrubTransientState(backupStories)
            val rawRoot = raw.canonicalFile
            FullBackupRestorePlanning.applyRestoredChapterFiles(backupStories, manifest.chapterFiles) { path ->
                // Paths come from untrusted backup JSON: only files inside the extraction count.
                File(rawRoot, path).canonicalFile.takeIf { it.isFile && it.path.startsWith(rawRoot.path + File.separator) }?.path
            }
            val takenAt = backupTakenAt(File(raw, "manifest.json")) ?: backupStories.mapNotNull { it.lastUpdated }.maxOrNull() ?: 0L
            synchronized(storage) { recoverLocked(backupStories, takenAt, keyFor) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e(error, "Chapter recovery failed")
            ActivityLog.record(
                ActivityCategory.RECOVERY,
                "recovery_failed",
                "error" to error.javaClass.simpleName,
                "message" to error.message,
            )
            BackupChapterRecoveryResult(
                "Recovery failed: ${error.message ?: error.javaClass.simpleName}. Your library was not changed.",
                succeeded = false,
            )
        } finally {
            if (workDir.exists() && !workDir.deleteRecursively()) Timber.w("Could not remove recovery work folder")
        }
    }

    private fun recoverLocked(
        backupStories: List<Story>,
        takenAt: Long,
        keyFor: (Story) -> (Chapter) -> String,
    ): BackupChapterRecoveryResult {
        val live = storage.loadLibraryForStartup().stories
        val plan = BackupChapterRecoveryPlanning.plan(live, backupStories, takenAt, keyFor, storage::chapterAvailable)
        val liveById = live.associateBy { it.id }
        val details = mutableListOf<String>()
        var recovered = 0
        var novels = 0
        val failed = mutableListOf<String>()
        plan.stories.forEach { storyPlan ->
            val before = checkNotNull(liveById[storyPlan.story.id])
            runCatching { applyStoryPlan(storyPlan, before, keyFor) }
                .onSuccess {
                    recovered += storyPlan.recovered
                    novels += 1
                    details += "${storyPlan.story.title}: ${storyPlan.recovered} chapter${if (storyPlan.recovered == 1) "" else "s"}"
                    ActivityLog.record(
                        ActivityCategory.RECOVERY,
                        "recovery_story_applied",
                        "storyId" to storyPlan.story.id,
                        "title" to storyPlan.story.title,
                        "restoredRemoved" to storyPlan.restoredRemoved,
                        "filled" to storyPlan.filled,
                        "chaptersBefore" to before.chapters.size,
                        "chaptersAfter" to storyPlan.story.chapters.size,
                    )
                }.onFailure { error ->
                    Timber.w(error, "Could not recover chapters into %s", storyPlan.story.id)
                    failed += storyPlan.story.title
                    ActivityLog.record(
                        ActivityCategory.RECOVERY,
                        "recovery_story_failed",
                        "storyId" to storyPlan.story.id,
                        "title" to storyPlan.story.title,
                        "error" to error.javaClass.simpleName,
                        "message" to error.message,
                    )
                }
        }
        ActivityLog.record(
            ActivityCategory.RECOVERY,
            "recovery_finished",
            "recoveredChapters" to recovered,
            "novels" to novels,
            "failedNovels" to failed.size,
            "skippedMissingNovels" to plan.skippedMissingStoryIds.size,
            "backupTakenAt" to takenAt,
        )
        val message =
            buildString {
                append(
                    if (novels == 0 && failed.isEmpty()) {
                        BackupChapterRecoveryPlanning.summary(plan).removeSuffix(".")
                    } else {
                        "Recovered $recovered chapter${if (recovered == 1) "" else "s"} in $novels novel${if (novels == 1) "" else "s"}"
                    },
                )
                if (failed.isNotEmpty()) append(". ${failed.size} novel(s) could not be recovered and were left unchanged")
                if (plan.skippedMissingStoryIds.isNotEmpty()) {
                    append(". Skipped ${plan.skippedMissingStoryIds.size} novel(s) that are no longer in your library")
                }
            }
        val failedDetails = failed.map { "$it: not changed (recovery failed)" }
        return BackupChapterRecoveryResult(message, details + failedDetails, succeeded = failed.isEmpty())
    }

    private fun applyStoryPlan(
        storyPlan: BackupChapterRecoveryPlanning.StoryPlan,
        before: Story,
        keyFor: (Story) -> (Chapter) -> String,
    ) {
        val storyId = storyPlan.story.id
        val referencedBefore = before.chapters.mapNotNullTo(HashSet()) { it.filePath }
        val copied = mutableListOf<String>()
        var written = false
        try {
            val chapters =
                storyPlan.story.chapters
                    .map { chapter ->
                        if (chapter.id !in storyPlan.adoptedChapterIds) {
                            chapter
                        } else {
                            val path = checkNotNull(storage.adoptChapterFile(storyId, chapter)) { "Missing file for chapter ${chapter.id}" }
                            copied += path
                            chapter.copy(filePath = path)
                        }
                    }.toMutableList()
            val recovered = storyPlan.story.copy(chapters = chapters)
            val covered = LegacyArchiveFoldPlanning.coverage(recovered, keyFor(recovered), storage::chapterAvailable)
            storyPlan.sources.forEach { source ->
                val lost = source.chapters.filter { it.downloaded && storage.chapterAvailable(it) && !covered(it) }
                check(lost.isEmpty()) { "${lost.size} backup chapter(s) of ${source.id} would not be readable" }
            }
            storage.addOrUpdateStory(recovered)
            written = true
        } finally {
            // A novel that was not written must not keep the files copied for it.
            if (!written) {
                copied.filterNot { it in referencedBefore }.forEach { path ->
                    runCatching { storage.deleteChapterFile(storyId, path) }
                }
            }
        }
    }

    /** The manifest's `exportDate`, read from its head so a large library is not parsed twice. */
    private fun backupTakenAt(manifest: File): Long? =
        runCatching {
            val head =
                manifest.bufferedReader().use { reader ->
                    CharArray(MANIFEST_HEAD_CHARS).let { buffer -> String(buffer, 0, reader.read(buffer)) }
                }
            EXPORT_DATE
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.let { Instant.parse(it).toEpochMilli() }
        }.getOrNull()

    private companion object {
        const val MANIFEST_HEAD_CHARS = 4096
        val EXPORT_DATE = Regex(""""exportDate"\s*:\s*"([^"]+)"""")
    }
}
