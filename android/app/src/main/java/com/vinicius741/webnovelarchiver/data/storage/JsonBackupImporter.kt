package com.vinicius741.webnovelarchiver.data.storage

import android.net.Uri
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import com.vinicius741.webnovelarchiver.data.backup.BackupMergePlanning
import com.vinicius741.webnovelarchiver.data.backup.JsonBackupValidation
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.io.IOException

internal class JsonBackupImporter(
    private val storage: AppStorage,
) {
    private val context get() = storage.context
    private val gson get() = storage.gson

    fun import(uri: Uri): String {
        val text =
            when (val input = readInput(uri)) {
                JsonBackupInput.NoSelection -> return context.getString(R.string.error_no_file_selected)
                is JsonBackupInput.Error -> return input.message
                is JsonBackupInput.Selected -> input.text
            }
        val payload = parsePayload(text) ?: return "Invalid backup file: not valid JSON"
        JsonBackupValidation.validate(payload)?.let { return it }
        val stories = parseStories(payload) ?: return "Invalid backup file: malformed story data"
        return importTransaction(payload, stories)
    }

    private fun readInput(uri: Uri): JsonBackupInput =
        try {
            context.contentResolver
                .openInputStream(uri)
                ?.use { BackupInputLimits.readUtf8(it, BackupInputLimits.MAX_JSON_BYTES, "JSON backup") }
                ?.let(JsonBackupInput::Selected)
                ?: JsonBackupInput.NoSelection
        } catch (error: IOException) {
            JsonBackupInput.Error("Invalid backup file: could not read input (${error.message ?: "I/O error"})")
        } catch (error: IllegalStateException) {
            JsonBackupInput.Error("Invalid backup file: ${error.message ?: "input limit exceeded"}")
        }

    private fun parsePayload(text: String): Map<String, Any?>? =
        runCatching {
            gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
        }.getOrNull()

    private fun parseStories(payload: Map<String, Any?>): MutableList<Story>? =
        runCatching {
            gson.fromJson<MutableList<Story>>(
                gson.toJson(payload["library"]),
                object : TypeToken<MutableList<Story>>() {}.type,
            )
        }.getOrNull()

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun importTransaction(
        payload: Map<String, Any?>,
        incomingStories: List<Story>,
    ): String =
        synchronized(storage) {
            val snapshotDir = File(storage.restoreRoot, "json_import_${System.currentTimeMillis()}")
            var snapshot: JsonImportSnapshot? = null
            var discardSnapshot = false
            try {
                snapshot = storage.snapshotLibraryAndTabs(snapshotDir)
                val counts = mergeStories(incomingStories)
                val importedTabs = mergeTabs(payload["tabs"])
                discardSnapshot = true
                "Imported ${counts.added + counts.updated} novels (${counts.added} new, ${counts.updated} updated) " +
                    "and $importedTabs tabs"
            } catch (error: Throwable) {
                discardSnapshot = snapshot?.let(storage::restoreJsonImportSnapshot) ?: true
                rethrowFatal(error)
                Timber.e(error, "JSON backup import failed; library rolled back to pre-import state")
                importFailure(error, discardSnapshot)
            } finally {
                if (discardSnapshot && snapshotDir.exists() && !snapshotDir.deleteRecursively()) {
                    Timber.w("Could not remove JSON import snapshot %s", snapshotDir.name)
                }
            }
        }

    private fun mergeStories(incomingStories: List<Story>): ImportCounts {
        val existing = storage.getLibrary()
        var counts = ImportCounts()
        incomingStories.forEach { incoming ->
            val index = existing.indexOfFirst { it.id == incoming.id }
            if (index >= 0) {
                existing[index] = BackupMergePlanning.mergeJsonBackupStory(incoming, existing[index])
                counts = counts.copy(updated = counts.updated + 1)
            } else {
                existing.add(BackupMergePlanning.mergeJsonBackupStory(incoming, null))
                counts = counts.copy(added = counts.added + 1)
            }
        }
        storage.saveLibrary(existing)
        return counts
    }

    private fun mergeTabs(rawTabs: Any?): Int {
        val tabs = rawTabs as? List<*> ?: return 0
        val incoming: List<Tab> =
            gson.fromJson(gson.toJson(tabs), object : TypeToken<MutableList<Tab>>() {}.type)
        val current = storage.getTabs()
        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val additions = incoming.filter { existingIds.add(it.id) }
        current += additions
        storage.saveTabs(current.mapIndexed { index, tab -> tab.copy(order = index) })
        return additions.size
    }

    private fun rethrowFatal(error: Throwable) {
        if (error is InterruptedException || error is CancellationException || error is VirtualMachineError) throw error
    }

    private fun importFailure(
        error: Throwable,
        rollbackSucceeded: Boolean,
    ): String =
        if (rollbackSucceeded) {
            "Import failed: ${error.message ?: "invalid backup"}. Your library was not changed."
        } else {
            "Import failed and automatic rollback could not be verified. Recovery data was preserved."
        }

    private data class ImportCounts(
        val added: Int = 0,
        val updated: Int = 0,
    )
}

private sealed interface JsonBackupInput {
    data class Selected(
        val text: String,
    ) : JsonBackupInput

    data class Error(
        val message: String,
    ) : JsonBackupInput

    data object NoSelection : JsonBackupInput
}
