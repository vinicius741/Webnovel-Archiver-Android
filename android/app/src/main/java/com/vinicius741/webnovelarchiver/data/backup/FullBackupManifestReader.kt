package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streams `manifest.json` into a [RawFullBackupManifest] without building the whole document in
 * memory. Stories are decoded typed one at a time (each through a small JSON tree, so a story that
 * is not an object with a string `id` is reported rather than coerced), and file indexes keep only
 * their string fields. Nothing here judges the content: [FullBackupManifestValidation] does.
 *
 * Errors mirror the former `Map` parse: malformed JSON and duplicate top-level keys throw
 * [JsonSyntaxException], an empty or `null` document is "manifest was empty", a manifest larger
 * than [maxBytes] fails the input limit, and other I/O errors propagate unchanged.
 */
object FullBackupManifestReader {
    private const val LABEL = "Full-backup manifest"

    /**
     * Every limit on an index is at most [BackupInputLimits.MAX_CHAPTER_FILES]; one entry beyond it
     * is enough for validation to see the overflow, so later entries are only counted.
     */
    private const val MAX_KEPT_INDEX_ENTRIES = BackupInputLimits.MAX_CHAPTER_FILES + 1

    fun read(
        gson: Gson,
        input: InputStream,
        maxBytes: Long,
    ): RawFullBackupManifest {
        val reader = JsonReader(InputStreamReader(LimitedInputStream(input.buffered(), maxBytes), Charsets.UTF_8))
        // The former Gson.fromJson(String) parse was lenient; keep accepting what it accepted.
        reader.strictness = Strictness.LENIENT
        return try {
            readDocument(gson, reader)
        } catch (error: MalformedJsonException) {
            throw JsonSyntaxException(error)
        } catch (error: EOFException) {
            throw JsonSyntaxException(error)
        }
    }

    private fun readDocument(
        gson: Gson,
        reader: JsonReader,
    ): RawFullBackupManifest {
        val first =
            try {
                reader.peek()
            } catch (_: EOFException) {
                JsonToken.END_DOCUMENT
            }
        check(first != JsonToken.END_DOCUMENT && first != JsonToken.NULL) { "Invalid full backup: manifest was empty" }
        if (first != JsonToken.BEGIN_OBJECT) throw JsonSyntaxException("Expected a JSON object but was $first")
        val elements = gson.getAdapter(JsonElement::class.java)
        val fields = ManifestFields()
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!seen.add(name)) throw JsonSyntaxException("duplicate key: $name")
            when (name) {
                "format" -> fields.format = elements.read(reader).stringOrNull()
                "version" -> fields.version = elements.read(reader).exactIntOrNull()
                "library" -> fields.library = readLibrary(gson, elements, reader)
                "config" -> fields.config = elements.read(reader).takeIf(JsonElement::isJsonObject)?.asJsonObject
                "chapterFiles" -> fields.chapterFiles = readIndex(elements, reader)
                "metricFiles" -> fields.metricFiles = readIndex(elements, reader)
                "coverFiles" -> fields.coverFiles = readIndex(elements, reader)
                "rewriteFiles" -> fields.rewriteFiles = readIndex(elements, reader)
                "missingContent" -> fields.missingContent = readIndex(elements, reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonSyntaxException("JSON document was not fully consumed.")
        return fields.toManifest()
    }

    /** Decodes a `library` array; any other value reads as a missing library. */
    private fun readLibrary(
        gson: Gson,
        elements: TypeAdapter<JsonElement>,
        reader: JsonReader,
    ): RawLibrary? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        val ids = mutableListOf<String>()
        val stories = mutableListOf<Story>()
        var size = 0
        var malformed = false
        var decodeError: JsonParseException? = null
        reader.beginArray()
        while (reader.hasNext()) {
            size += 1
            if (size > BackupInputLimits.MAX_STORIES) {
                reader.skipValue()
                continue
            }
            val element = elements.read(reader)
            val id = (element as? JsonObject)?.get("id")?.stringOrNull()
            if (id == null) {
                malformed = true
                continue
            }
            ids += id
            try {
                stories += gson.fromJson(element, Story::class.java)
            } catch (error: JsonParseException) {
                if (decodeError == null) decodeError = error
            }
        }
        reader.endArray()
        return RawLibrary(ids = ids, stories = stories, size = size, malformed = malformed, decodeError = decodeError)
    }

    private fun readIndex(
        elements: TypeAdapter<JsonElement>,
        reader: JsonReader,
    ): RawIndex? {
        // A JSON null reads as an absent key, as it did through the former Map parse.
        if (reader.peek() == JsonToken.NULL) {
            reader.skipValue()
            return null
        }
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return RawIndex(entries = null, size = 0)
        }
        val entries = mutableListOf<RawIndexEntry?>()
        var size = 0
        reader.beginArray()
        while (reader.hasNext()) {
            size += 1
            if (size > MAX_KEPT_INDEX_ENTRIES) {
                reader.skipValue()
                continue
            }
            val entry = elements.read(reader) as? JsonObject
            entries +=
                entry?.let {
                    RawIndexEntry(
                        storyId = it.get("storyId")?.stringOrNull(),
                        path = it.get("path")?.stringOrNull(),
                        chapterId = it.get("chapterId")?.stringOrNull(),
                        kind = it.get("kind")?.stringOrNull(),
                    )
                }
        }
        reader.endArray()
        return RawIndex(entries = entries, size = size)
    }

    /** A JSON string's value; numbers and other types are not strings (the old `as? String`). */
    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.exactIntOrNull(): Int? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { BackupInputLimits.exactInt(it.asNumber) }

    private class ManifestFields {
        var format: String? = null
        var version: Int? = null
        var library: RawLibrary? = null
        var config: JsonObject? = null
        var chapterFiles: RawIndex? = null
        var metricFiles: RawIndex? = null
        var coverFiles: RawIndex? = null
        var rewriteFiles: RawIndex? = null
        var missingContent: RawIndex? = null

        fun toManifest() =
            RawFullBackupManifest(
                format = format,
                version = version,
                library = library,
                config = config,
                chapterFiles = chapterFiles,
                metricFiles = metricFiles,
                coverFiles = coverFiles,
                rewriteFiles = rewriteFiles,
                missingContent = missingContent,
            )
    }

    /** Fails the restore once more than [maxBytes] have been read, like [BackupInputLimits.copyWithLimit]. */
    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            total += bytes
            check(total <= maxBytes) { "$LABEL exceeds the ${maxBytes / (1024L * 1024L)} MB input limit" }
        }
    }
}
