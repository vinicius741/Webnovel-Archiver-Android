package com.vinicius741.webnovelarchiver.tts.kokoro

import java.io.File

/** Where the Kokoro install stands; [KokoroModelInstaller.state] publishes it. */
internal sealed interface KokoroModelState {
    /** [downloadedBytes] of a stopped download are kept, and the next try continues from there. */
    data class NotInstalled(
        val downloadedBytes: Long = 0L,
    ) : KokoroModelState

    data class Downloading(
        val bytes: Long,
        val total: Long,
    ) : KokoroModelState

    data object Verifying : KokoroModelState

    data class Unpacking(
        val bytes: Long,
        val total: Long,
    ) : KokoroModelState

    data class Installed(
        val bytes: Long,
    ) : KokoroModelState

    data class Failed(
        val reason: String,
    ) : KokoroModelState
}

internal object KokoroInstallPlanning {
    private const val MB = 1_000_000.0

    /** Extra room on top of the files themselves, so the install never fills the phone. */
    private const val FREE_SPACE_MARGIN_BYTES = 200_000_000L

    /**
     * Where a tar entry goes inside [target], or null for anything outside the model's own folder:
     * an absolute path, a `..` step, or another top-level folder. The archive's top folder is dropped.
     */
    fun entryTarget(
        target: File,
        entryName: String,
        topFolder: String = KokoroModel.DIR_NAME,
    ): File? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (entryName.startsWith("/") || parts.firstOrNull() != topFolder || parts.any { it == ".." }) return null
        val inside = parts.drop(1)
        return if (inside.isEmpty()) target else File(target, inside.joinToString(File.separator))
    }

    /** Bytes the install still needs: the rest of the download, the unpacked files, and a margin. */
    fun bytesNeeded(downloadedBytes: Long): Long =
        (KokoroModel.ARCHIVE_BYTES - downloadedBytes).coerceAtLeast(0L) + KokoroModel.UNPACKED_BYTES + FREE_SPACE_MARGIN_BYTES

    /** Downloaded bytes the install can keep: a partial or complete archive, but never an oversized one. */
    fun reusableBytes(partialBytes: Long): Long = if (partialBytes in 1..KokoroModel.ARCHIVE_BYTES) partialBytes else 0L

    /** A partial download is only worth resuming while it is shorter than the whole archive. */
    fun resumeFrom(partialBytes: Long): Long = if (partialBytes in 1 until KokoroModel.ARCHIVE_BYTES) partialBytes else 0L

    fun describe(state: KokoroModelState): String =
        when (state) {
            is KokoroModelState.NotInstalled ->
                if (state.downloadedBytes > 0L) {
                    "Download paused at ${megabytes(state.downloadedBytes)} of ${megabytes(KokoroModel.ARCHIVE_BYTES)}."
                } else {
                    "Not downloaded. The voices need a one-time ${megabytes(KokoroModel.ARCHIVE_BYTES)} download."
                }
            is KokoroModelState.Downloading -> {
                val done = percent(state.bytes, state.total)
                "Downloading $done (${megabytes(state.bytes)} of ${megabytes(state.total)})"
            }
            KokoroModelState.Verifying -> "Checking the download"
            is KokoroModelState.Unpacking -> "Unpacking ${percent(state.bytes, state.total)}"
            is KokoroModelState.Installed -> "Installed. Uses ${megabytes(state.bytes)} on this phone."
            is KokoroModelState.Failed -> state.reason
        }

    fun megabytes(bytes: Long): String = "${(bytes / MB).toLong()} MB"

    private fun percent(
        bytes: Long,
        total: Long,
    ): String = if (total <= 0L) "" else "${(bytes * 100 / total).coerceIn(0L, 100L)}%"
}
