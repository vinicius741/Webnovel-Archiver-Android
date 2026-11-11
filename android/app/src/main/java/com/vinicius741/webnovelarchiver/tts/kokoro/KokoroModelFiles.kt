package com.vinicius741.webnovelarchiver.tts.kokoro

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where the downloaded Kokoro model lives: app files, outside the library storage root, so full
 * backups never carry it and the system never clears it as cache.
 */
internal class KokoroModelFiles(
    root: File,
) {
    constructor(context: Context) : this(File(context.filesDir, MODELS_DIR))

    val modelsDir: File = root
    val dir = File(root, KokoroModel.DIR_NAME)
    val staging = File(root, "${KokoroModel.DIR_NAME}.staging")
    val partialArchive = File(root, "${KokoroModel.DIR_NAME}.tar.bz2.part")
    val model = File(dir, "model.onnx")
    val voices = File(dir, "voices.bin")
    val tokens = File(dir, "tokens.txt")
    val dataDir = File(dir, "espeak-ng-data")
    private val marker = File(dir, INSTALLED_MARKER)

    /** Only a fully unpacked and checked model carries the marker, so a half-done install never loads. */
    val isInstalled: Boolean get() = marker.isFile && model.isFile && voices.isFile && tokens.isFile && dataDir.isDirectory

    fun installedBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MODELS_DIR = "tts_models"
        const val INSTALLED_MARKER = ".installed"

        /** The app ships Kokoro's native code for these ABIs only (see `abiFilters` in build.gradle). */
        private val supportedAbis = setOf("arm64-v8a", "x86_64")

        val isDeviceSupported: Boolean get() = Build.SUPPORTED_ABIS.any { it in supportedAbis }
    }
}

/** The one Kokoro model the app installs: sherpa-onnx's English v0.19 export. */
internal object KokoroModel {
    const val DIR_NAME = "kokoro-en-v0_19"
    const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    const val ARCHIVE_BYTES = 319_625_534L
    const val ARCHIVE_SHA256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7"

    /** Sum of the unpacked files, for progress and the free-space check. */
    const val UNPACKED_BYTES = 369_315_617L

    /** Files an install must contain, relative to the model directory. */
    val requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab")
}
