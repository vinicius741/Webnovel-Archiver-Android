package com.vinicius741.webnovelarchiver.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Re-encodes a cover image as lossy WebP at [AiCoverPlanning.COMPACT_COVER_WEBP_QUALITY], keeping
 * its pixel size. Callers decide when through [AiCoverPlanning.compactCover]; this only does the
 * Android codec work and returns null on any failure so the caller keeps the original bytes.
 */
internal object CoverWebpEncoder {
    fun encode(bytes: ByteArray): ByteArray? {
        val bitmap =
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Cover decode ran out of memory; keeping the original")
                null
            } ?: return null
        return try {
            val out = ByteArrayOutputStream(bytes.size / 2)
            if (bitmap.compress(webpFormat(), AiCoverPlanning.COMPACT_COVER_WEBP_QUALITY, out)) out.toByteArray() else null
        } catch (error: OutOfMemoryError) {
            Timber.w(error, "Cover WebP encode ran out of memory; keeping the original")
            null
        } finally {
            bitmap.recycle()
        }
    }

    // Before API 30 the plain WEBP format is lossy for any quality below 100.
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}
