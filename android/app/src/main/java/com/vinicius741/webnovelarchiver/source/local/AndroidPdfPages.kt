package com.vinicius741.webnovelarchiver.source.local

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File

/** PDF text through the platform [PdfRenderer], which reads page text from Android 15 (API 35). */
internal object AndroidPdfPages : PdfPages {
    private const val COVER_WIDTH_PX = 600
    private const val COVER_JPEG_QUALITY = 88
    private const val COVER_MAX_HEIGHT_PX = 1200

    override val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override fun text(file: File): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            throw UnsupportedOperationException("Reading PDF text needs Android 15 or newer.")
        }
        return pageTexts(file)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun pageTexts(file: File): List<String> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
                }
            }
        }

    override fun firstPageImage(file: File): ByteArray? =
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return@use null
                    renderer.openPage(0).use { page ->
                        val height =
                            (COVER_WIDTH_PX.toLong() * page.height / page.width.coerceAtLeast(1))
                                .toInt()
                                .coerceIn(1, COVER_MAX_HEIGHT_PX)
                        val bitmap = Bitmap.createBitmap(COVER_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            ByteArrayOutputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
                                out.toByteArray()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }.getOrNull()
}
