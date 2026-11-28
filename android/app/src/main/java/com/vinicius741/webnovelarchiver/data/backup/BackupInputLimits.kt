package com.vinicius741.webnovelarchiver.data.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BackupInputLimits {
    const val MAX_JSON_BYTES = 50L * 1024L * 1024L
    const val MAX_ZIP_INPUT_BYTES = 2_000_000_000L
    const val MAX_ZIP_ENTRIES = 200_000
    const val MAX_CHAPTER_FILES = MAX_ZIP_ENTRIES - 1
    const val MAX_MANIFEST_BYTES = MAX_JSON_BYTES
    const val MAX_ZIP_ENTRY_BYTES = 100_000_000L
    const val MAX_STORIES = 50_000
    private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L

    /**
     * Zip-bomb guard that replaces a fixed extracted-size cap: a full backup may expand to at most
     * this many times its compressed size. Real backups expand about 2.3x (chapter HTML; the most
     * compressible chapter measured 3.6x) and a JSON manifest stays far below this, while a deflate
     * bomb expands up to ~1,000x per entry.
     */
    const val MAX_EXPANSION_RATIO = 100L

    /** Small archives (a manifest-only backup) may always expand this far, whatever their ratio. */
    private const val MIN_EXPANSION_ALLOWANCE_BYTES = 64L * 1024L * 1024L

    /** Allocation unit assumed for each extracted file, so many small chapters cannot overrun the budget. */
    private const val FILE_BLOCK_BYTES = 4L * 1024L

    /**
     * Disk the restore may fill while extracting. The extraction is the restore's only copy of the
     * backup's data: it is renamed into the staged root and then beside the live root (see
     * `RestoreTreeMove`), so every byte above the reserve is usable. The live library stays on disk
     * until the commit, so its space is already excluded from [usableBytes].
     */
    fun extractionBudget(usableBytes: Long): Long {
        val afterReserve = usableBytes - FREE_SPACE_RESERVE_BYTES
        check(afterReserve > 0L) { "Not enough free space to restore this backup" }
        return afterReserve
    }

    /** Uncompressed bytes a ZIP of [compressedBytes] may expand to before it is treated as a ZIP bomb. */
    fun maxExpandedBytes(compressedBytes: Long): Long {
        val ratioLimit =
            if (compressedBytes > Long.MAX_VALUE / MAX_EXPANSION_RATIO) Long.MAX_VALUE else compressedBytes * MAX_EXPANSION_RATIO
        return maxOf(ratioLimit, MIN_EXPANSION_ALLOWANCE_BYTES)
    }

    /** Disk an extracted file of [fileBytes] occupies, rounded up to whole blocks (an empty file still costs one). */
    fun allocatedBytes(fileBytes: Long): Long {
        val blocks = (maxOf(fileBytes, 1L) + FILE_BLOCK_BYTES - 1L) / FILE_BLOCK_BYTES
        return blocks * FILE_BLOCK_BYTES
    }

    fun hasSwapSpace(
        usableBytes: Long,
        stagedBytes: Long,
    ): Boolean =
        stagedBytes >= 0L &&
            usableBytes >= FREE_SPACE_RESERVE_BYTES &&
            usableBytes - FREE_SPACE_RESERVE_BYTES >= stagedBytes

    /** Gson represents untyped JSON numbers as doubles, so reject fractional/overflow versions. */
    fun exactInt(value: Any?): Int? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        if (asDouble < Int.MIN_VALUE.toDouble() || asDouble > Int.MAX_VALUE.toDouble()) return null
        return asDouble.toInt()
    }

    fun isAllowedFullBackupEntry(
        name: String,
        directory: Boolean,
    ): Boolean {
        if (isMalformedEntryName(name)) return false
        val normalized = name.trimEnd('/')
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        if (normalized == "manifest.json") return !directory
        if (normalized == "novels") return directory
        if (normalized == "metrics") return directory
        if (normalized == "covers") return directory
        if (normalized == "chapter_rewrites") return directory
        if (!normalized.startsWith("novels/") && !normalized.startsWith("metrics/") &&
            !normalized.startsWith("covers/") && !normalized.startsWith("chapter_rewrites/")
        ) {
            return false
        }
        if (directory) return parts.size == 2 && parts[1].isNotBlank()
        // novels/<story>/<chapter>.html, metrics/<story>.json, covers/<story>.<img>, or
        // chapter_rewrites/<story>/<stem>/applied.html — depth and extension distinguish the trees.
        return when {
            normalized.startsWith("novels/") -> parts.size == 3 && parts[1].isNotBlank() && parts[2].endsWith(".html", ignoreCase = true)
            normalized.startsWith("covers/") -> isAllowedCoverFile(parts)
            normalized.startsWith("chapter_rewrites/") -> isAllowedRewriteFile(parts)
            else -> parts.size == 2 && parts[1].isNotBlank() && parts[1].endsWith(".json", ignoreCase = true)
        }
    }

    /** Names that are blank, padded, or contain path tricks never name a real zip entry. */
    private fun isMalformedEntryName(name: String): Boolean =
        name.isBlank() ||
            name != name.trim() ||
            name.startsWith('/') ||
            '\\' in name ||
            '\u0000' in name

    /** Cover entries sit flat at `covers/<name>` with a known image extension. */
    private fun isAllowedCoverFile(parts: List<String>): Boolean {
        // The size check matters: without it a dotted directory component (`covers/a.jpg/b.txt`)
        // would validate against the directory's extension and smuggle a nested entry past the
        // one-level tree invariant the manifest validation and cover cleanup rely on.
        if (parts.size != 2) return false
        val name = parts[1]
        return name.isNotBlank() && name.substringAfterLast('.', "").lowercase() in COVER_EXTENSIONS
    }

    /**
     * Rewrite entries follow the store's exact layout: `chapter_rewrites/<story>/manifest.json` or
     * `chapter_rewrites/<story>/<stem>/applied[-<generation>].html`. Drafts are excluded from
     * backups by design, so `draft*.html` (and everything else) is rejected.
     */
    private fun isAllowedRewriteFile(parts: List<String>): Boolean =
        (
            parts.size == 3 && parts[1].isNotBlank() && parts[2] == "manifest.json"
        ) ||
            (
                parts.size == 4 &&
                    parts[1].isNotBlank() &&
                    parts[2].isNotBlank() &&
                    APPLIED_FILE_NAME.matches(parts[3])
            )

    /**
     * Legacy `applied.html` plus generation-suffixed names. The suffix class must cover every
     * character `safeName(operationId)` can emit — operation ids are UUIDs, so it includes `-` and
     * `.`; traversal is already blocked by the blank/`.`/`..` component rejection above.
     */
    private val APPLIED_FILE_NAME = Regex("applied(-[A-Za-z0-9._-]{1,32})?\\.html")

    /** File extensions accepted for generated cover entries inside a full backup. */
    private val COVER_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun readUtf8(
        input: InputStream,
        maxBytes: Long,
        label: String,
    ): String {
        val out = ByteArrayOutputStream()
        copyWithLimit(input, out, maxBytes, label)
        return out.toString(Charsets.UTF_8.name())
    }

    fun copyWithLimit(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        label: String,
    ): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= maxBytes) { "$label exceeds the ${maxBytes / (1024L * 1024L)} MB input limit" }
            output.write(buffer, 0, count)
        }
        return total
    }
}
