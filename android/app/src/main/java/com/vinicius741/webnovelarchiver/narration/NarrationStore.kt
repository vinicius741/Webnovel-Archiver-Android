package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Generated chapter audio on disk, outside the library and the cache: it is regenerable and large,
 * so backups skip it, and only the user deletes it.
 *
 * Each chapter folder holds immutable version folders and a `current` file naming the published
 * one. A version is built in place (clips first, `index.json` last) and published by replacing
 * `current`, so a process death leaves either the old pack or the new one, never a mix. Playback
 * [retain]s the version it reads, so a regeneration published meanwhile does not delete it.
 */
@Suppress("TooManyFunctions") // The one owner of the pack folders: layout, publication, retention and cleanup.
class NarrationStore(
    val root: File,
) {
    private val packsRoot = File(root, PACKS_DIR)
    private val gson = Gson()
    private val lock = Any()
    private val packs = HashMap<String, NarrationPack>()
    private val retained = HashMap<String, Int>()
    private val loaded = CompletableDeferred<Unit>()

    private val changes = MutableStateFlow(0L)

    /** Bumps whenever a pack is published or deleted. */
    val revision: StateFlow<Long> get() = changes

    /** Reads every published pack into memory. Call once, off the main thread. */
    fun load() {
        val found = mutableListOf<NarrationPack>()
        packsRoot.listFiles()?.filter(File::isDirectory)?.forEach { storyDir ->
            storyDir.listFiles()?.filter(File::isDirectory)?.forEach { chapterDir ->
                readCurrent(chapterDir)?.let(found::add)
            }
        }
        synchronized(lock) {
            packs.clear()
            found.forEach { packs[key(it.storyId, it.chapterId)] = it }
        }
        loaded.complete(Unit)
        changes.value++
    }

    suspend fun awaitLoaded() = loaded.await()

    val isLoaded: Boolean get() = loaded.isCompleted

    fun pack(
        storyId: String,
        chapterId: String,
    ): NarrationPack? = synchronized(lock) { packs[key(storyId, chapterId)] }

    fun packsFor(storyId: String): List<NarrationPack> = synchronized(lock) { packs.values.filter { it.storyId == storyId } }

    fun allPacks(): List<NarrationPack> = synchronized(lock) { packs.values.toList() }

    fun totalBytes(): Long = allPacks().sumOf { it.index.bytes }

    fun chapterDir(
        storyId: String,
        chapterId: String,
    ): File = File(File(packsRoot, NarrationPlanning.folderName(storyId)), NarrationPlanning.folderName(chapterId))

    /** A new, empty version folder holding [recipe]. */
    fun createVersion(recipe: NarrationRecipe): File {
        val dir = File(chapterDir(recipe.storyId, recipe.chapterId), VERSION_PREFIX + UUID.randomUUID().toString().take(VERSION_ID_CHARS))
        if (!dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        writeAtomically(File(dir, RECIPE_FILE), gson.toJson(recipe).toByteArray())
        return dir
    }

    /** The version folder [version] of a chapter with its recipe, if it exists and is unpublished. */
    fun openVersion(
        storyId: String,
        chapterId: String,
        version: String,
    ): Pair<File, NarrationRecipe>? {
        val dir = File(chapterDir(storyId, chapterId), version)
        if (!dir.isDirectory || File(dir, INDEX_FILE).isFile) return null
        val recipe = readJson(File(dir, RECIPE_FILE), NarrationRecipe::class.java) ?: return null
        if (recipe.storyId != storyId || recipe.chapterId != chapterId) return null
        return dir to recipe
    }

    fun clipFile(
        versionDir: File,
        sentence: Int,
    ): File = File(versionDir, "%04d.$CLIP_EXTENSION".format(sentence))

    fun titleFile(versionDir: File): File = File(versionDir, "$TITLE_NAME.$CLIP_EXTENSION")

    /** Writes a clip under a temporary name and renames it, so a present clip is always whole. */
    internal fun writeClip(
        file: File,
        clip: TtsPcmClip,
    ) {
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(clip.sampleRate)
                .array()
        writeAtomically(file, header + clip.pcm)
    }

    internal fun readClip(file: File): TtsPcmClip? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_BYTES) return null
            val sampleRate = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
            TtsPcmClip(sampleRate, bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }.onFailure { Timber.w(it, "Narration clip read failed") }.getOrNull()
    }

    /**
     * Completes [versionDir] with [index] and makes it the chapter's published pack. The previous
     * version is deleted unless playback still reads it.
     */
    fun publish(
        recipe: NarrationRecipe,
        versionDir: File,
        index: NarrationPackIndex,
    ): NarrationPack {
        writeAtomically(File(versionDir, INDEX_FILE), gson.toJson(index).toByteArray())
        val chapterDir = versionDir.parentFile ?: throw IOException("Version folder has no parent")
        writeAtomically(File(chapterDir, CURRENT_FILE), versionDir.name.toByteArray())
        val pack = NarrationPack(recipe.storyId, recipe.chapterId, versionDir.name, versionDir, recipe, index)
        val previous =
            synchronized(lock) {
                packs.put(key(recipe.storyId, recipe.chapterId), pack)
            }
        if (previous != null && previous.dir != versionDir) deleteIfUnretained(previous.dir)
        changes.value++
        return pack
    }

    /** Playback reads [pack]: keep its files until [release]. */
    fun retain(pack: NarrationPack) {
        synchronized(lock) { retained[pack.dir.path] = (retained[pack.dir.path] ?: 0) + 1 }
    }

    fun release(pack: NarrationPack) {
        val superseded =
            synchronized(lock) {
                val count = (retained[pack.dir.path] ?: 0) - 1
                if (count > 0) retained[pack.dir.path] = count else retained.remove(pack.dir.path)
                count <= 0 && packs[key(pack.storyId, pack.chapterId)]?.dir != pack.dir
            }
        if (superseded) pack.dir.deleteRecursively()
    }

    /** Deletes a chapter's audio: every version, unless playback reads one (that one goes on release). */
    fun deleteChapter(
        storyId: String,
        chapterId: String,
    ) {
        synchronized(lock) { packs.remove(key(storyId, chapterId)) }
        val dir = chapterDir(storyId, chapterId)
        File(dir, CURRENT_FILE).delete()
        dir.listFiles()?.forEach { deleteIfUnretained(it) }
        if (dir.list().isNullOrEmpty()) dir.delete()
        changes.value++
    }

    fun deleteStory(storyId: String) {
        val chapterIds = synchronized(lock) { packs.values.filter { it.storyId == storyId }.map { it.chapterId } }
        chapterIds.forEach { deleteChapter(storyId, it) }
        val storyDir = File(packsRoot, NarrationPlanning.folderName(storyId))
        storyDir.listFiles()?.forEach { chapterDir ->
            if (File(chapterDir, CURRENT_FILE).isFile) return@forEach
            chapterDir.listFiles()?.forEach { deleteIfUnretained(it) }
            if (chapterDir.list().isNullOrEmpty()) chapterDir.delete()
        }
        if (storyDir.list().isNullOrEmpty()) storyDir.delete()
    }

    /** Drops an unpublished version folder (a cancelled or outdated job). */
    fun discardVersion(versionDir: File) {
        if (File(versionDir, INDEX_FILE).isFile) return
        versionDir.deleteRecursively()
    }

    /**
     * Startup cleanup, run only once the library is ready. It deletes unpublished versions no job is
     * building ([activeVersions]), superseded versions, and stray temporary files. It never deletes a
     * published pack, even one whose story is no longer in the library.
     */
    fun sweep(activeVersions: Set<String>) {
        packsRoot.listFiles()?.filter(File::isDirectory)?.forEach { storyDir ->
            storyDir.listFiles()?.filter(File::isDirectory)?.forEach { sweepChapter(it, activeVersions) }
            if (storyDir.list().isNullOrEmpty()) storyDir.delete()
        }
    }

    private fun sweepChapter(
        chapterDir: File,
        activeVersions: Set<String>,
    ) {
        val current = File(chapterDir, CURRENT_FILE).takeIf(File::isFile)?.readText()?.trim()
        chapterDir.listFiles()?.forEach { entry ->
            when {
                entry.name.endsWith(TEMP_SUFFIX) -> entry.delete()
                entry.isDirectory && entry.name != current && entry.path !in activeVersions -> deleteIfUnretained(entry)
                entry.isDirectory -> entry.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.forEach(File::delete)
            }
        }
        if (chapterDir.list().isNullOrEmpty()) chapterDir.delete()
    }

    private fun deleteIfUnretained(dir: File) {
        val inUse = synchronized(lock) { retained.containsKey(dir.path) }
        if (!inUse) dir.deleteRecursively()
    }

    private fun readCurrent(chapterDir: File): NarrationPack? {
        val version = File(chapterDir, CURRENT_FILE).takeIf(File::isFile)?.readText()?.trim() ?: return null
        val dir = File(chapterDir, version)
        val recipe = readJson(File(dir, RECIPE_FILE), NarrationRecipe::class.java) ?: return null
        val index = readJson(File(dir, INDEX_FILE), NarrationPackIndex::class.java) ?: return null
        if (recipe.storyId.isEmpty() || recipe.chapterId.isEmpty()) return null
        return NarrationPack(recipe.storyId, recipe.chapterId, version, dir, recipe, index)
    }

    private fun <T> readJson(
        file: File,
        type: Class<T>,
    ): T? =
        if (!file.isFile) {
            null
        } else {
            runCatching {
                val text = file.readText()
                val current =
                    when (type) {
                        NarrationRecipe::class.java -> NarrationLegacyJson.recipe(text)
                        NarrationPackIndex::class.java -> NarrationLegacyJson.index(text)
                        else -> text
                    }
                gson.fromJson(current, type)
            }.onFailure { Timber.w(it, "Narration file unreadable: %s", file.name) }
                .getOrNull()
        }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, target.name + TEMP_SUFFIX)
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not write ${target.name}")
        }
    }

    private fun key(
        storyId: String,
        chapterId: String,
    ) = "$storyId\u0000$chapterId"

    companion object {
        const val CODEC_PCM = "pcm"
        const val PACKS_DIR = "packs"
        private const val CURRENT_FILE = "current"
        private const val RECIPE_FILE = "recipe.json"
        private const val INDEX_FILE = "index.json"
        private const val VERSION_PREFIX = "v-"
        private const val VERSION_ID_CHARS = 12
        private const val CLIP_EXTENSION = "pcm"
        private const val TITLE_NAME = "title"
        private const val TEMP_SUFFIX = ".tmp"
        private const val HEADER_BYTES = 4
    }
}
