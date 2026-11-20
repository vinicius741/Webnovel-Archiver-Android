package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Generated chapter audio on disk, outside the library and the cache: it is regenerable and large,
 * so backups skip it, and only the user deletes it.
 *
 * Clips are compressed with [codec] (AAC-LC in the app; `NarrationClipFormat`) when it takes them;
 * packs made before compression hold PCM clips, which still play, and [recompress] converts them.
 * Without a codec (unit tests) clips are written as PCM.
 *
 * Each chapter folder holds immutable version folders and a `current` file naming the published
 * one. A version is built in place (clips first, `index.json` last) and published by replacing
 * `current`, so a process death leaves either the old pack or the new one, never a mix. Playback
 * [retain]s the version it reads, so a regeneration published meanwhile does not delete it.
 */
@Suppress("TooManyFunctions") // The one owner of the pack folders: layout, publication, retention and cleanup.
class NarrationStore internal constructor(
    val root: File,
    private val codec: NarrationClipCodec? = null,
) {
    private val packsRoot = File(root, PACKS_DIR)
    private val gson = Gson()
    private val lock = Any()

    /**
     * Serializes everything that changes a chapter's published files (publish, conversion,
     * deletion). [lock] guards only the maps, so readers (screens included) never wait on a disk sync.
     */
    private val publishLock = Any()
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

    /**
     * Sentence [sentence]'s clip in [versionDir]: the PCM file an older version wrote when it is
     * there (a version resumed after an update can hold both kinds), else the compressed name.
     */
    fun clipFile(
        versionDir: File,
        sentence: Int,
    ): File = resolve(versionDir, "%04d".format(sentence))

    fun titleFile(versionDir: File): File = resolve(versionDir, TITLE_NAME)

    private fun resolve(
        versionDir: File,
        name: String,
    ): File = File(versionDir, "$name.$LEGACY_CLIP_EXTENSION").takeIf(File::isFile) ?: File(versionDir, "$name.$CLIP_EXTENSION")

    /**
     * Writes a clip under a temporary name and renames it, so a present clip is always whole. It is
     * compressed when the codec takes it, else stored as PCM.
     */
    internal fun writeClip(
        file: File,
        clip: TtsPcmClip,
    ) {
        val encoded =
            codec?.let { codec ->
                runCatching { codec.encode(clip) }
                    .onFailure { Timber.w(it, "Narration clip encode failed; storing PCM") }
                    .getOrNull()
            }
        writeAtomically(file, encoded?.let(NarrationClipFormat::write) ?: NarrationClipFormat.legacy(clip.sampleRate, clip.pcm))
    }

    internal fun readClip(file: File): TtsPcmClip? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < NarrationClipFormat.LEGACY_HEADER_BYTES) return null
            if (NarrationClipFormat.isCompressed(bytes)) {
                val encoded = NarrationClipFormat.read(bytes) ?: return null
                val pcm = codec?.decode(encoded) ?: return null
                TtsPcmClip(encoded.sampleRate, pcm)
            } else {
                val sampleRate = NarrationClipFormat.legacySampleRate(bytes).takeIf { it > 0 } ?: return null
                TtsPcmClip(sampleRate, bytes.copyOfRange(NarrationClipFormat.LEGACY_HEADER_BYTES, bytes.size))
            }
        }.onFailure { Timber.w(it, "Narration clip read failed") }.getOrNull()
    }

    /** The sample rate and length of the clip in [file] without decoding it; null when it is missing or unreadable. */
    internal fun clipInfo(file: File): NarrationClipInfo? = if (file.isFile) NarrationClipFormat.info(file) else null

    /**
     * Puts the clip in [from] at [to]: a compressed clip is copied as it is (compressing it again
     * would lose quality), a PCM one is compressed on the way. False when [from] is unreadable.
     */
    internal fun copyClip(
        from: File,
        to: File,
    ): Boolean {
        val info = clipInfo(from) ?: return false
        if (info.compressed) {
            val bytes = runCatching { from.readBytes() }.getOrNull() ?: return false
            if (NarrationClipFormat.read(bytes) == null) return false
            writeAtomically(to, bytes)
        } else {
            writeClip(to, readClip(from) ?: return false)
        }
        return true
    }

    /**
     * The index of [versionDir]'s clips for [recipe], read from their headers; null when a clip is
     * missing or the clips disagree on the sample rate.
     */
    internal fun indexFor(
        versionDir: File,
        recipe: NarrationRecipe,
        generatedAt: Long,
    ): NarrationPackIndex? {
        val sentences = recipe.sentences.indices.map { clipInfo(clipFile(versionDir, it)) ?: return null }
        val title = recipe.title?.let { clipInfo(titleFile(versionDir)) ?: return null }
        val clips = sentences + listOfNotNull(title)
        val rates = clips.filter { it.frames > 0 }.map { it.sampleRate }.toSet()
        if (rates.size > 1) return null
        val sampleRate = rates.firstOrNull() ?: DEFAULT_SAMPLE_RATE

        fun ms(info: NarrationClipInfo) = TtsPcmPlanning.framesToMs(info.frames, sampleRate)
        val durations = sentences.map(::ms)
        val titleMs = title?.let(::ms)
        val files = recipe.sentences.indices.map { clipFile(versionDir, it) } + listOfNotNull(recipe.title?.let { titleFile(versionDir) })
        return NarrationPackIndex(
            codec = if (clips.all { it.codec == NarrationClipFormat.CODEC_AAC_LC }) CODEC_AAC else CODEC_PCM,
            sampleRate = sampleRate,
            sentenceDurationsMs = durations,
            titleDurationMs = titleMs,
            totalDurationMs = durations.sum() + (titleMs ?: 0L),
            bytes = files.sumOf { it.length() },
            generatedAt = generatedAt,
        )
    }

    /** Whether new clips are compressed, so [recompress] has something to do. */
    internal val compresses: Boolean get() = codec != null

    /**
     * Rewrites [pack]'s PCM clips compressed into a new version and publishes it in place of
     * [pack], keeping its recipe, durations and generation time. Nothing is published if the
     * chapter's pack changed meanwhile (regenerated or deleted); the new version is dropped then.
     * It also stops, publishing nothing, at the first clip that cannot be compressed, or once
     * [keepGoing] turns false (the generator has work).
     */
    internal fun recompress(
        pack: NarrationPack,
        keepGoing: () -> Boolean = { true },
    ): Recompression {
        val codec = codec ?: return Recompression.Unconvertible
        val versionDir = createVersion(pack.recipe)
        try {
            val sources =
                pack.recipe.sentences.indices
                    .map { clipFile(pack.dir, it) to clipFile(versionDir, it) } +
                    listOfNotNull(pack.recipe.title?.let { titleFile(pack.dir) to titleFile(versionDir) })
            for ((from, to) in sources) {
                if (!keepGoing()) return Recompression.Interrupted
                compressClip(codec, from, to)?.let { return it }
            }
            val index = indexFor(versionDir, pack.recipe, pack.index.generatedAt) ?: return Recompression.Unconvertible
            if (index.codec != CODEC_AAC) return Recompression.Unconvertible
            // Checked again here: a job that started on the last clip may want the old clips to reuse.
            if (!keepGoing()) return Recompression.Interrupted
            return publishIfCurrent(pack, versionDir, index)?.let(Recompression::Converted) ?: Recompression.Superseded
        } catch (io: IOException) {
            // Deleting the chapter meanwhile also deletes this version mid-write: nothing to publish.
            if (pack(pack.storyId, pack.chapterId)?.dir != pack.dir) return Recompression.Superseded
            throw io
        } finally {
            if (!File(versionDir, INDEX_FILE).isFile) versionDir.deleteRecursively()
        }
    }

    /**
     * [from]'s clip at [to], compressed: a compressed clip is copied as it is, a PCM one encoded.
     * Unlike [copyClip] it never falls back to PCM. Null when done, else why it could not be: a
     * damaged clip or an unsupported sample rate is the pack's problem, a refusal the codec's.
     */
    private fun compressClip(
        codec: NarrationClipCodec,
        from: File,
        to: File,
    ): Recompression? {
        val info = clipInfo(from) ?: return Recompression.Unconvertible
        if (info.compressed) return if (copyClip(from, to)) null else Recompression.Unconvertible
        if (!codec.supports(info.sampleRate)) return Recompression.Unconvertible
        val clip = readClip(from) ?: return Recompression.Unconvertible
        val encoded = codec.encode(clip) ?: return Recompression.CodecFailed
        writeAtomically(to, NarrationClipFormat.write(encoded))
        return null
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
        val pack = NarrationPack(recipe.storyId, recipe.chapterId, versionDir.name, versionDir, recipe, index)
        // Files and map change together under the publish lock, so [recompress] can never interleave with them.
        val previous =
            synchronized(publishLock) {
                writeIndexAndCurrent(versionDir, index)
                synchronized(lock) { packs.put(key(recipe.storyId, recipe.chapterId), pack) }
            }
        if (previous != null && previous.dir != versionDir) deleteIfUnretained(previous.dir)
        changes.value++
        return pack
    }

    /** [publish], but only while [expected] is still the chapter's pack; null (nothing published) otherwise. */
    private fun publishIfCurrent(
        expected: NarrationPack,
        versionDir: File,
        index: NarrationPackIndex,
    ): NarrationPack? {
        val key = key(expected.storyId, expected.chapterId)
        val pack = NarrationPack(expected.storyId, expected.chapterId, versionDir.name, versionDir, expected.recipe, index)
        // The publish lock spans the check and the switch, so a regeneration or deletion is never overwritten.
        synchronized(publishLock) {
            if (synchronized(lock) { packs[key]?.dir } != expected.dir) return null
            writeIndexAndCurrent(versionDir, index)
            synchronized(lock) { packs[key] = pack }
        }
        deleteIfUnretained(expected.dir)
        changes.value++
        return pack
    }

    /** Completes [versionDir] with [index], then points the chapter's `current` at it. */
    private fun writeIndexAndCurrent(
        versionDir: File,
        index: NarrationPackIndex,
    ) {
        writeAtomically(File(versionDir, INDEX_FILE), gson.toJson(index).toByteArray())
        val chapterDir = versionDir.parentFile ?: throw IOException("Version folder has no parent")
        writeAtomically(File(chapterDir, CURRENT_FILE), versionDir.name.toByteArray())
    }

    /**
     * Playback reads [pack]: keeps its files until [release] and returns it. When a conversion
     * replaced it after playback looked it up, its files may be going, so the replacement (same
     * recipe and durations) is retained and returned instead. Null when the chapter's audio was
     * regenerated or deleted meanwhile.
     */
    fun retain(pack: NarrationPack): NarrationPack? =
        synchronized(lock) {
            val current = packs[key(pack.storyId, pack.chapterId)]
            val target =
                when {
                    current?.dir == pack.dir || retained.containsKey(pack.dir.path) -> pack
                    current != null && current.recipe == pack.recipe -> current
                    else -> return null
                }
            retained[target.dir.path] = (retained[target.dir.path] ?: 0) + 1
            target
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
        synchronized(publishLock) {
            synchronized(lock) { packs.remove(key(storyId, chapterId)) }
            val dir = chapterDir(storyId, chapterId)
            File(dir, CURRENT_FILE).delete()
            dir.listFiles()?.forEach { deleteIfUnretained(it) }
            if (dir.list().isNullOrEmpty()) dir.delete()
        }
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
        const val CODEC_AAC = "aac"
        const val PACKS_DIR = "packs"
        private const val CURRENT_FILE = "current"
        private const val RECIPE_FILE = "recipe.json"
        private const val INDEX_FILE = "index.json"
        private const val VERSION_PREFIX = "v-"
        private const val VERSION_ID_CHARS = 12
        private const val CLIP_EXTENSION = "clip"
        private const val LEGACY_CLIP_EXTENSION = "pcm"
        private const val TITLE_NAME = "title"
        private const val TEMP_SUFFIX = ".tmp"
        private const val DEFAULT_SAMPLE_RATE = 24_000
    }
}

/** How [NarrationStore.recompress] ended. */
internal sealed interface Recompression {
    /** The compressed pack was published in place of the old one. */
    data class Converted(
        val pack: NarrationPack,
    ) : Recompression

    /** The generator got work first; nothing was published, and the chapter can be tried again. */
    data object Interrupted : Recompression

    /** The chapter was regenerated or deleted meanwhile; nothing was published. */
    data object Superseded : Recompression

    /** This pack cannot be compressed (a damaged clip, an unsupported sample rate); it stays as it is. */
    data object Unconvertible : Recompression

    /** The codec refused or failed on a clip it should take: a sign it does not work on this phone. */
    data object CodecFailed : Recompression
}
