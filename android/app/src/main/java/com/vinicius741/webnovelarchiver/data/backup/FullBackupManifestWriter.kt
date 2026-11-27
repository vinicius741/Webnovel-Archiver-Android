package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonWriter
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.OutputStream
import java.io.OutputStreamWriter

/** Everything a version-2 manifest records. Chapter files are not listed: restore derives them. */
class FullBackupManifestContent(
    val exportDate: String,
    /** Stories as exported (transient file paths already cleared). */
    val library: List<Story>,
    val config: Map<String, Any?>,
    val metricFiles: List<RestoredMetricFileIndex>,
    val coverFiles: List<RestoredCoverFileIndex>,
    val missingContent: List<FullBackupContentPlanning.MissingContent>,
)

/**
 * Writes the version-2 manifest as compact JSON straight into the ZIP entry, one story at a time,
 * so neither the whole document nor a copy of it is built in memory.
 *
 * Version 2 drops version 1's `chapterFiles` list, which repeated every downloaded chapter's ids,
 * index, title and path. Restore rebuilds it from `library` and `missingContent`
 * ([FullBackupManifestValidation]). HTML escaping is off: the manifest is never embedded in HTML,
 * and `<`, `>`, `&`, `=` and `'` in titles would otherwise cost six bytes each.
 */
object FullBackupManifestWriter {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Writes the manifest to [output] and flushes it, leaving [output] open for the next ZIP entry. */
    fun write(
        output: OutputStream,
        content: FullBackupManifestContent,
    ) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered()
        val json = JsonWriter(writer)
        json.beginObject()
        json.name("format").value(FullBackupManifestValidation.FORMAT)
        json.name("version").value(FullBackupManifestValidation.CURRENT_VERSION.toLong())
        json.name("exportDate").value(content.exportDate)
        json.name("library").beginArray()
        content.library.forEach { story -> gson.toJson(story, Story::class.java, json) }
        json.endArray()
        json.name("config")
        gson.toJson(content.config, Map::class.java, json)
        // Per-story trend history (`metrics/<id>.json`). Optional on restore: older backups omit
        // the key and restore with empty history (the storage layer's missing-file path).
        writeStoryPaths(json, "metricFiles", content.metricFiles.map { it.storyId to it.path })
        // Generated AI covers at the story's own relative aiCoverPath, so the ZIP layout matches the
        // on-disk covers/ tree. Optional on restore: stories fall back to the source cover URL.
        writeStoryPaths(json, "coverFiles", content.coverFiles.map { it.storyId to it.path })
        // R10: content the library reports as available but that could not be found at export
        // time. Version 2 restore also uses the chapter entries to leave those chapters out of
        // the derived chapter index.
        json.name("missingContent").beginArray()
        content.missingContent.forEach { missing ->
            json.beginObject()
            json.name("kind").value(missing.kind)
            json.name("storyId").value(missing.storyId)
            missing.chapterId?.let { json.name("chapterId").value(it) }
            missing.title?.let { json.name("title").value(it) }
            json.endObject()
        }
        json.endArray()
        json.endObject()
        json.flush()
    }

    private fun writeStoryPaths(
        json: JsonWriter,
        name: String,
        entries: List<Pair<String, String>>,
    ) {
        json.name(name).beginArray()
        entries.forEach { (storyId, path) ->
            json
                .beginObject()
                .name("storyId")
                .value(storyId)
                .name("path")
                .value(path)
                .endObject()
        }
        json.endArray()
    }
}
