package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import coil3.load
import coil3.request.crossfade
import com.vinicius741.webnovelarchiver.data.storage.LocalImageRevision

/**
 * A soft, darkened blur of an image used as a backdrop (story hero, cover viewer). The image is
 * shrunk to a few pixels and drawn back up with bilinear filtering, which blurs on every API level
 * without RenderEffect. With [fadeTo] set, the bottom fades into that color so content below
 * blends into the screen.
 */
internal class BlurredBackdropView(
    context: Context,
    private val dim: Int = Color.argb(DEFAULT_DIM_ALPHA, 0, 0, 0),
    private val fadeTo: Int? = null,
) : AppCompatImageView(context) {
    private var tiny: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val dst = Rect()

    init {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        tiny = drawable?.let(::tinyCopy)
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        fadeTo?.let { color ->
            fadePaint.shader =
                LinearGradient(0f, h * FADE_START, 0f, h.toFloat(), color and TRANSPARENT_MASK, color, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = tiny ?: return
        val viewRatio = width.toFloat() / height.coerceAtLeast(1)
        val bmpRatio = bmp.width.toFloat() / bmp.height
        val (w, h) = if (bmpRatio > viewRatio) (height * bmpRatio).toInt() to height else width to (width / bmpRatio).toInt()
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.drawColor(dim)
        if (fadeTo != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
    }

    private fun tinyCopy(drawable: Drawable): Bitmap? = tinySoftwareCopy(drawable, BLUR_PX)

    /** Loads [source] (URL, File or Bitmap) without a crossfade, so the blur is taken from the finished image. */
    fun loadSource(source: Any?) {
        if (source == null) {
            setImageDrawable(null)
            return
        }
        load(source) {
            crossfade(false)
            if (source is java.io.File) memoryCacheKey("${source.absolutePath}:${LocalImageRevision.current(source)}")
        }
    }

    private companion object {
        const val BLUR_PX = 10
        const val DEFAULT_DIM_ALPHA = 140
        const val FADE_START = 0.35f
        const val TRANSPARENT_MASK = 0x00FFFFFF
    }
}
