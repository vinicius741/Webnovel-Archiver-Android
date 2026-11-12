package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import com.vinicius741.webnovelarchiver.ui.ThemeManager

/**
 * Now Playing's background: the cover drawn as a tiny copy scaled back up (a cheap blur on every
 * API level), darkened and warmed, then faded into the app background towards the bottom so the
 * controls stay readable. Never draws the sharp image itself.
 */
internal class PlayerBackdrop(
    context: Context,
) : AppCompatImageView(context) {
    private var tiny: Bitmap? = null
    private val dst = Rect()
    private val imagePaint =
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            val matrix = ColorMatrix().apply { setSaturation(SATURATION) }
            val dim = ColorMatrix().apply { setScale(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f) }
            matrix.postConcat(dim)
            colorFilter = ColorMatrixColorFilter(matrix)
        }
    private val fadePaint = Paint()

    init {
        setBackgroundColor(ThemeManager.colors.background)
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
        val bg = ThemeManager.colors.background
        fadePaint.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                intArrayOf(bg and TOP_ALPHA_MASK, bg and MID_ALPHA_MASK, bg),
                floatArrayOf(0f, MID_STOP, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        tiny?.let { bmp ->
            val viewRatio = width.toFloat() / height.coerceAtLeast(1)
            val bmpRatio = bmp.width.toFloat() / bmp.height
            val (w, h) =
                if (bmpRatio > viewRatio) {
                    (height * bmpRatio).toInt() to height
                } else {
                    width to (width / bmpRatio).toInt()
                }
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(bmp, null, dst, imagePaint)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
    }

    private fun tinyCopy(drawable: Drawable): Bitmap? {
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val h = (TINY_PX * ih / iw).coerceAtLeast(1)
        return runCatching {
            createBitmap(TINY_PX, h).also { bmp ->
                val old = drawable.copyBounds()
                drawable.setBounds(0, 0, TINY_PX, h)
                drawable.draw(Canvas(bmp))
                drawable.bounds = old
            }
        }.getOrNull()
    }

    private companion object {
        const val TINY_PX = 10
        const val SATURATION = 1.3f
        const val BRIGHTNESS = 0.42f
        const val MID_STOP = 0.7f
        const val TOP_ALPHA_MASK = 0x33FFFFFF
        const val MID_ALPHA_MASK = 0xD9FFFFFF.toInt()
    }
}
