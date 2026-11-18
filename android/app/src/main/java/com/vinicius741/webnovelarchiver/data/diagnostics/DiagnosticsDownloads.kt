package com.vinicius741.webnovelarchiver.data.diagnostics

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.vinicius741.webnovelarchiver.data.backup.FileMimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which earlier diagnostics copies in Downloads to remove: the newest [keepPerKind] of each log kind stay. */
object DiagnosticsDownloadsPlanning {
    const val FOLDER = "Webnovel Archiver"
    const val KEEP_PER_KIND = 3

    private val exportName = Regex("""^(webnovel_[a-z_]+_log)_(\d+)(?: \(\d+\))?\.json$""")

    fun namesToPrune(
        names: List<String>,
        keepPerKind: Int = KEEP_PER_KIND,
    ): List<String> =
        names
            .mapNotNull { name ->
                exportName.matchEntire(name)?.let { match ->
                    Triple(name, match.groupValues[1], match.groupValues[2].toLongOrNull() ?: 0L)
                }
            }.groupBy { it.second }
            .values
            .flatMap { exports -> exports.sortedByDescending { it.third }.drop(keepPerKind) }
            .map { it.first }
}

/**
 * Copies the diagnostics exports into the public Downloads/Webnovel Archiver folder, so they can be
 * attached from another app's file picker when sharing them directly does not work (the T3 Code
 * Android app rejects files shared into it: pingdotgg/t3code#38607). Needs API 29+ (MediaStore
 * Downloads, no storage permission); older devices keep share-only.
 */
object DiagnosticsDownloads {
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val folderLabel: String get() = "Downloads/${DiagnosticsDownloadsPlanning.FOLDER}"

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun save(
        context: Context,
        files: List<File>,
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        files.forEach { file -> insert(resolver, file) }
        runCatching { prune(resolver) }
    }

    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${DiagnosticsDownloadsPlanning.FOLDER}/"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insert(
        resolver: ContentResolver,
        file: File,
    ) {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, FileMimeTypes.forFilename(file.name))
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create ${file.name} in Downloads")
        runCatching {
            val output = resolver.openOutputStream(uri) ?: error("Could not write ${file.name} to Downloads")
            output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }.onFailure { resolver.delete(uri, null, null) }
            .getOrThrow()
    }

    /** Only this app's own entries are visible without a storage permission, which is all we prune. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun prune(resolver: ContentResolver) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val entries = mutableMapOf<String, Long>()
        resolver
            .query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) entries[cursor.getString(nameColumn)] = cursor.getLong(idColumn)
            }
        DiagnosticsDownloadsPlanning.namesToPrune(entries.keys.toList()).forEach { name ->
            val id = entries.getValue(name)
            resolver.delete(collection, "${MediaStore.Downloads._ID} = ?", arrayOf(id.toString()))
        }
    }
}
