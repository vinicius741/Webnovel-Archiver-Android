package com.vinicius741.webnovelarchiver.source.local

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.math.sqrt

/**
 * PDF pages through the platform [PdfRenderer], which reads page text from Android 15 (API 35).
 * Scanned pages are rendered and read by ML Kit's on-device text recognizer (Latin script), whose
 * model ships inside the app: it works offline, without Google Play services, and only changes
 * when the app updates its ML Kit version.
 */
internal object AndroidPdfPages : PdfPages {
    private const val COVER_WIDTH_PX = 600
    private const val COVER_JPEG_QUALITY = 88
    private const val COVER_MAX_HEIGHT_PX = 1200

    /** About 3 megapixels: a novel page's letters come out 25-35 px tall, well above what ML Kit needs. */
    private const val OCR_PIXELS = 3_000_000.0
    private const val OCR_MAX_SIDE_PX = 4096

    override val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override fun text(file: File): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            throw UnsupportedOperationException("Reading PDF text needs Android 15 or newer.")
        }
        return pageTexts(file)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun pageTexts(file: File): List<String> =
        OpenPdf(file).use { pdf ->
            (0 until pdf.renderer.pageCount).map { index ->
                pdf.renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
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

    override fun recognizer(file: File): PdfPageRecognizer = MlKitPageRecognizer(OpenPdf(file))

    /**
     * An open PDF. A file the renderer cannot open is a fact about the book (damaged, not a PDF, or
     * password-protected), so it is an [IllegalStateException] that skips this book; failing to open
     * the file itself stays an IOException that fails the scan.
     */
    private class OpenPdf(
        file: File,
    ) : Closeable {
        private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer: PdfRenderer = openRenderer()

        private fun openRenderer(): PdfRenderer {
            var opened: PdfRenderer? = null
            try {
                opened = PdfRenderer(descriptor)
                return opened
            } catch (error: IOException) {
                throw IllegalStateException("This PDF is damaged or is not a PDF.", error)
            } catch (error: SecurityException) {
                throw IllegalStateException("This PDF is password-protected.", error)
            } finally {
                if (opened == null) descriptor.close()
            }
        }

        override fun close() {
            try {
                renderer.close()
            } finally {
                descriptor.close()
            }
        }
    }

    /**
     * Renders one page at a time; the ML Kit client is created only when a page needs recognizing.
     * A page that fails is a [LocalSourceException]: the scan fails rather than going on without the
     * book, which would mark its chapters removed.
     */
    private class MlKitPageRecognizer(
        private val pdf: OpenPdf,
    ) : PdfPageRecognizer {
        private var client: TextRecognizer? = null

        override val pageCount: Int get() = pdf.renderer.pageCount

        override fun recognize(index: Int): List<OcrLine> {
            val bitmap = render(index)
            try {
                val recognizer = client ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { client = it }
                val text =
                    try {
                        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    } catch (error: ExecutionException) {
                        throw LocalSourceException("Text recognition failed on page ${index + 1}: ${error.cause?.message}", error)
                    } catch (error: CancellationException) {
                        // ML Kit's task, not the import's coroutine, was cancelled.
                        throw LocalSourceException("Text recognition stopped on page ${index + 1}", error)
                    }
                return text.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        // A line without a box takes its block's, so it still sorts near its neighbours.
                        val box = line.boundingBox ?: block.boundingBox
                        OcrLine(line.text, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0)
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        private fun render(index: Int): Bitmap =
            pdf.renderer.openPage(index).use { page ->
                // Page sizes are in points: about OCR_PIXELS whatever the page's size, keeping its shape.
                val pageWidth = page.width.coerceAtLeast(1)
                val pageHeight = page.height.coerceAtLeast(1)
                val scale =
                    minOf(sqrt(OCR_PIXELS / (pageWidth.toDouble() * pageHeight)), OCR_MAX_SIDE_PX.toDouble() / maxOf(pageWidth, pageHeight))
                val bitmap =
                    Bitmap.createBitmap(
                        (pageWidth * scale).toInt().coerceAtLeast(1),
                        (pageHeight * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                var rendered = false
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    rendered = true
                } finally {
                    if (!rendered) bitmap.recycle()
                }
                bitmap
            }

        override fun close() {
            try {
                client?.close()
            } finally {
                pdf.close()
            }
        }
    }
}
