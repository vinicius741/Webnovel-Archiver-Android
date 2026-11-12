package com.vinicius741.webnovelarchiver.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.transition.CrossfadeDrawable

/**
 * A tiny software copy of [drawable] ([widthPx] wide, aspect kept), drawn back up with bilinear
 * filtering to make a cheap blur. Handles what Coil hands an ImageView: a crossfade (its final
 * image is used, not the empty start) and hardware bitmaps, which cannot be drawn into a software
 * canvas and are copied first. Returns null when the image has no size or cannot be read.
 */
internal fun tinySoftwareCopy(
    drawable: Drawable,
    widthPx: Int,
): Bitmap? {
    val source = (drawable as? CrossfadeDrawable)?.end ?: drawable
    val iw = source.intrinsicWidth
    val ih = source.intrinsicHeight
    if (iw <= 0 || ih <= 0) return null
    val height = (widthPx * ih / iw).coerceAtLeast(1)
    return runCatching {
        val bitmap = (source as? BitmapDrawable)?.bitmap
        if (bitmap != null) {
            val readable =
                if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            readable.scale(widthPx, height, filter = true).also { if (readable !== bitmap) readable.recycle() }
        } else {
            createBitmap(widthPx, height).also { bmp ->
                val old = source.copyBounds()
                source.setBounds(0, 0, widthPx, height)
                source.draw(Canvas(bmp))
                source.bounds = old
            }
        }
    }.getOrNull()
}
