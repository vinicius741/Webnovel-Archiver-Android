package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.CharacterPortrait
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.domain.sha256Hex
import java.io.File

/**
 * Per-novel character list and portraits under `ai_characters/<story>/`: `roster.json` holds the
 * last "Find characters" result, and `portraits/<character id>/` holds each portrait as an image
 * plus a JSON sidecar with its prompt, written image first. Device-local like saved AI cover
 * versions: not part of backups, and deleted with the novel.
 */
internal class CharacterStore(
    private val root: File,
    private val safeName: (String) -> String,
) {
    @Synchronized
    fun roster(storyId: String): List<StoryCharacter>? =
        runCatching {
            val file = rosterFile(storyId)
            if (!file.isFile) return null
            JsonParser
                .parseString(file.readText())
                .asJsonObject
                .getAsJsonArray("characters")
                .mapNotNull { runCatching { readCharacter(it.asJsonObject) }.getOrNull() }
        }.getOrNull()

    @Synchronized
    fun saveRoster(
        storyId: String,
        characters: List<StoryCharacter>,
    ) {
        val json =
            JsonObject().apply {
                addProperty("version", 1)
                add("characters", JsonArray().apply { characters.forEach { add(writeCharacter(it)) } })
            }
        AtomicFileWrites.writeText(rosterFile(storyId), json.toString())
    }

    @Synchronized
    fun savePortrait(
        storyId: String,
        characterId: String,
        prompt: String,
        bytes: ByteArray,
        mediaType: String?,
    ): String {
        val id = sha256Hex(bytes)
        val dir = portraitDir(storyId, characterId)
        val meta = File(dir, "$id.json")
        if (meta.isFile) return id
        val image = File(dir, "$id.${AiCoverPlanning.coverFileExtension(mediaType)}")
        AtomicFileWrites.writeBytes(image, bytes)
        val json =
            JsonObject().apply {
                addProperty("prompt", prompt)
                mediaType?.let { addProperty("mediaType", it) }
                addProperty("imageFile", image.name)
            }
        AtomicFileWrites.writeText(meta, json.toString())
        return id
    }

    /** Newest first. */
    @Synchronized
    fun portraits(
        storyId: String,
        characterId: String,
    ): List<CharacterPortrait> =
        portraitDir(storyId, characterId)
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { meta ->
                runCatching {
                    val json = JsonParser.parseString(meta.readText()).asJsonObject
                    val name = json.get("imageFile")?.asString ?: return@runCatching null
                    if (name != File(name).name || name.contains("..")) return@runCatching null
                    val image = File(meta.parentFile, name).takeIf { it.isFile } ?: return@runCatching null
                    CharacterPortrait(
                        meta.nameWithoutExtension,
                        json.get("prompt")?.asString.orEmpty(),
                        json.get("mediaType")?.takeUnless { it.isJsonNull }?.asString,
                        image,
                        meta.lastModified(),
                    )
                }.getOrNull()
            }.sortedWith(compareByDescending<CharacterPortrait> { it.createdAt }.thenBy { it.id })

    /** The newest portrait of every character that has one, for the list's thumbnails. */
    @Synchronized
    fun latestPortraits(storyId: String): Map<String, File> =
        File(storyDir(storyId), PORTRAITS)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> portraits(storyId, dir.name).firstOrNull()?.let { dir.name to it.image } }
            .toMap()

    @Synchronized
    fun deletePortrait(
        storyId: String,
        characterId: String,
        portraitId: String,
    ) {
        val dir = portraitDir(storyId, characterId)
        dir
            .listFiles()
            .orEmpty()
            .filter { it.nameWithoutExtension == portraitId }
            .forEach { it.delete() }
    }

    @Synchronized
    fun deleteAll(storyId: String) {
        storyDir(storyId).deleteRecursively()
    }

    private fun storyDir(storyId: String) = File(File(root, "ai_characters"), safeName(storyId))

    private fun rosterFile(storyId: String) = File(storyDir(storyId), "roster.json")

    private fun portraitDir(
        storyId: String,
        characterId: String,
    ) = File(File(storyDir(storyId), PORTRAITS), safeName(characterId))

    private companion object {
        const val PORTRAITS = "portraits"
    }
}

private fun readCharacter(json: JsonObject): StoryCharacter =
    StoryCharacter(
        id = json.get("id").asString,
        name = json.get("name").asString,
        aliases = json.getAsJsonArray("aliases")?.map { it.asString }.orEmpty(),
        role = CharacterRole.fromKey(json.get("role")?.asString),
        bio = json.get("bio")?.asString.orEmpty(),
        mentions = json.get("mentions")?.asInt ?: 0,
        firstChapter = json.get("firstChapter")?.takeUnless { it.isJsonNull }?.asInt,
        lastChapter = json.get("lastChapter")?.takeUnless { it.isJsonNull }?.asInt,
        keyChapters = json.getAsJsonArray("keyChapters")?.map { it.asInt }.orEmpty(),
    )

private fun writeCharacter(character: StoryCharacter): JsonObject =
    JsonObject().apply {
        addProperty("id", character.id)
        addProperty("name", character.name)
        add("aliases", JsonArray().apply { character.aliases.forEach(::add) })
        addProperty("role", character.role.key)
        addProperty("bio", character.bio)
        addProperty("mentions", character.mentions)
        character.firstChapter?.let { addProperty("firstChapter", it) }
        character.lastChapter?.let { addProperty("lastChapter", it) }
        add("keyChapters", JsonArray().apply { character.keyChapters.forEach(::add) })
    }
