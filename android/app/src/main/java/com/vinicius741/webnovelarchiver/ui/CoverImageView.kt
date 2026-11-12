package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap

/**
 * A cover that is never cropped: the art is fitted inside the view, and any space left by an
 * odd-shaped cover is filled with a soft, darkened blur of the same art (a tiny downscaled copy
 * drawn back up with bilinear filtering, which works on every API level without RenderEffect).
 * Most covers are 2:3 and simply fill the view.
 */
internal class CoverImageView(
    context: Context,
) : AppCompatImageView(context) {
    private var backdrop: Bitmap? = null
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        backdrop = drawable?.let(::tinyCopy)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        backdrop?.let { bmp ->
            // Center-crop the tiny copy over the whole view; its upscale is the blur.
            val viewRatio = width.toFloat() / height.coerceAtLeast(1)
            val bmpRatio = bmp.width.toFloat() / bmp.height
            val (w, h) =
                if (bmpRatio > viewRatio) {
                    (height * bmpRatio).toInt() to height
                } else {
                    width to (width / bmpRatio).toInt()
                }
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(bmp, null, dst, backdropPaint)
            canvas.drawColor(BACKDROP_DIM)
        }
        super.onDraw(canvas)
    }

    private fun tinyCopy(drawable: Drawable): Bitmap? {
        val art = (drawable as? coil3.transition.CrossfadeDrawable)?.end ?: drawable
        val iw = art.intrinsicWidth
        val ih = art.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        // Exactly 2:3 art fills the view on its own; skip the extra draw.
        if (kotlin.math.abs(ih.toFloat() / iw - COVER_RATIO) < RATIO_TOLERANCE) return null
        return tinySoftwareCopy(drawable, BACKDROP_PX)
    }

    private companion object {
        const val BACKDROP_PX = 12
        const val COVER_RATIO = 1.5f
        const val RATIO_TOLERANCE = 0.02f
        val BACKDROP_DIM = Color.argb(110, 0, 0, 0)
    }
}
