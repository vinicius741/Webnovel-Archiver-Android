package com.vinicius741.webnovelarchiver.tts.kokoro

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where a downloaded built-in model lives: app files, outside the library storage root, so full
 * backups never carry it and the system never clears it as cache. One folder per [BuiltInModel].
 */
internal class KokoroModelFiles(
    root: File,
    val builtIn: BuiltInModel = BuiltInModel.KOKORO_V019,
) {
    constructor(context: Context, builtIn: BuiltInModel = BuiltInModel.KOKORO_V019) : this(File(context.filesDir, MODELS_DIR), builtIn)

    val modelsDir: File = root
    val dir = File(root, builtIn.dirName)
    val staging = File(root, "${builtIn.dirName}.staging")
    val partialArchive = File(root, "${builtIn.dirName}.tar.bz2.part")
    val model = File(dir, "model.onnx")
    val voices = File(dir, "voices.bin")
    val tokens = File(dir, "tokens.txt")
    val dataDir = File(dir, "espeak-ng-data")

    /** Kokoro v1.0's US English lexicon, read before espeak-ng. */
    val lexicon = File(dir, "lexicon-us-en.txt")
    private val marker = File(dir, INSTALLED_MARKER)

    /** Only a fully installed and checked model carries the marker, so a half-done install never loads. */
    val isInstalled: Boolean get() = marker.isFile && requiredFiles().all { File(dir, it).exists() }

    /**
     * Bytes an older install here lacks: files added to the model since (Phonon's new voices), or
     * 0 when nothing was installed or the install is complete. Those files are fetched, the rest kept.
     */
    fun missingFromOlderInstall(): Long {
        val pkg = builtIn.modelPackage as? ModelPackage.Files ?: return 0L
        if (!marker.isFile) return 0L
        return pkg.files.filterNot(::isAtHand).sumOf { it.bytes }
    }

    /** Bytes of [ModelPackage.Files] an install will not download: complete in staging or in an older install. */
    fun reusableFileBytes(pkg: ModelPackage.Files): Long = pkg.files.filter(::isAtHand).sumOf { it.bytes }

    /** [file] is complete in staging, or in the install folder an update moves it from. */
    private fun isAtHand(file: ModelFile): Boolean =
        File(staging, file.path).length() == file.bytes || File(dir, file.path).length() == file.bytes

    fun installedBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun requiredFiles(): List<String> =
        when (val pkg = builtIn.modelPackage) {
            is ModelPackage.Archive -> pkg.requiredFiles
            is ModelPackage.Files -> pkg.files.map { it.path }
        }

    companion object {
        const val MODELS_DIR = "tts_models"
        const val INSTALLED_MARKER = ".installed"

        /** The app ships Kokoro's native code for these ABIs only (see `abiFilters` in build.gradle). */
        private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /** Phonon's runtime is built for 64-bit only (see `scripts/phonon/build-android-lib.sh`). */
        private val phononAbis = setOf("arm64-v8a", "x86_64")

        val isDeviceSupported: Boolean get() = Build.SUPPORTED_ABIS.any { it in supportedAbis }

        fun isDeviceSupported(model: BuiltInModel): Boolean =
            when (model.runtime) {
                BuiltInRuntime.SHERPA_KOKORO -> isDeviceSupported
                // The primary ABI, since the 64-bit library cannot load into a 32-bit process. Its arm64
                // build uses the dotprod and fp16 instructions, which crash the whole app (SIGILL) on a CPU
                // without them, as under the x86 emulator's ARM translation: check the CPU says it has them.
                BuiltInRuntime.PHONON ->
                    when (Build.SUPPORTED_ABIS.firstOrNull()) {
                        "arm64-v8a" -> phononCpuReady
                        in phononAbis -> true
                        else -> false
                    }
            }

        private val phononCpuReady: Boolean by lazy {
            PhononCpu.hasArmFeatures(runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""))
        }
    }
}

/** Kokoro v0.19's download (see [BuiltInModel.modelPackage]), under the names the original code used. */
internal object KokoroModel {
    private val pkg: ModelPackage.Archive get() = BuiltInModel.KOKORO_V019.modelPackage as ModelPackage.Archive
    val DIR_NAME: String = BuiltInModel.KOKORO_V019.dirName
    val ARCHIVE_URL: String get() = pkg.url
    val ARCHIVE_BYTES: Long get() = pkg.downloadBytes
    val ARCHIVE_SHA256: String get() = pkg.sha256
    val UNPACKED_BYTES: Long get() = pkg.unpackedBytes
    val requiredFiles: List<String> get() = pkg.requiredFiles
}

/** What Phonon's arm64 library needs from the CPU (see `scripts/phonon/build-android-lib.sh`). */
internal object PhononCpu {
    /**
     * Whether every core [cpuinfo] (`/proc/cpuinfo`) lists has the dot-product (`asimddp`) and
     * half-precision (`asimdhp`, `fphp`) instructions. No feature lines at all reads as "no".
     */
    fun hasArmFeatures(cpuinfo: String): Boolean {
        val cores =
            cpuinfo
                .lineSequence()
                .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
                .map {
                    it
                        .substringAfter(':')
                        .trim()
                        .split(Regex("\\s+"))
                        .toSet()
                }.toList()
        return cores.isNotEmpty() && cores.all { "asimddp" in it && ("asimdhp" in it || "fphp" in it) }
    }
}
