package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

private const val MIN_ZOOM = 1f
private const val DOUBLE_TAP_ZOOM = 2.25f
private const val MAX_ZOOM = 5f

internal class ZoomableImageView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : AppCompatImageView(context, attrs, defStyleAttr) {
        private val drawMatrix = Matrix()
        private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
        private val gestureDetector = GestureDetector(context, TapListener())
        private var currentZoom = MIN_ZOOM
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            post { resetTransform() }
        }

        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            post { resetTransform() }
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            resetTransform()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    val (x, y) = event.center()
                    lastX = x
                    lastY = y
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = event.center()
                    if (!scaleDetector.isInProgress) {
                        val dx = x - lastX
                        val dy = y - lastY
                        if (dx != 0f || dy != 0f) {
                            drawMatrix.postTranslate(dx, dy)
                            keepImageInBounds()
                            imageMatrix = drawMatrix
                            moved = true
                        }
                    }
                    lastX = x
                    lastY = y
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && !scaleDetector.isInProgress) performClick()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }

                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun resetTransform() {
            val drawable = drawable ?: return
            val availableWidth = (width - paddingLeft - paddingRight).toFloat()
            val availableHeight = (height - paddingTop - paddingBottom).toFloat()
            if (availableWidth <= 0f || availableHeight <= 0f) return
            val imageWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return
            val imageHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return

            val baseScale = minOf(availableWidth / imageWidth, availableHeight / imageHeight)
            val dx = paddingLeft + (availableWidth - imageWidth * baseScale) / 2f
            val dy = paddingTop + (availableHeight - imageHeight * baseScale) / 2f

            drawMatrix.reset()
            drawMatrix.postScale(baseScale, baseScale)
            drawMatrix.postTranslate(dx, dy)
            currentZoom = MIN_ZOOM
            imageMatrix = drawMatrix
        }

        private fun zoomTo(
            targetZoom: Float,
            focusX: Float,
            focusY: Float,
        ) {
            val nextZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val scaleBy = nextZoom / currentZoom
            drawMatrix.postScale(scaleBy, scaleBy, focusX, focusY)
            currentZoom = nextZoom
            keepImageInBounds()
            imageMatrix = drawMatrix
        }

        private fun keepImageInBounds() {
            val rect = displayRect() ?: return
            val availableLeft = paddingLeft.toFloat()
            val availableTop = paddingTop.toFloat()
            val availableRight = (width - paddingRight).toFloat()
            val availableBottom = (height - paddingBottom).toFloat()
            val dx = correction(rect.left, rect.right, availableLeft, availableRight)
            val dy = correction(rect.top, rect.bottom, availableTop, availableBottom)
            drawMatrix.postTranslate(dx, dy)
        }

        private fun displayRect(): RectF? {
            val drawable = drawable ?: return null
            val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            drawMatrix.mapRect(rect)
            return rect
        }

        private fun correction(
            start: Float,
            end: Float,
            limitStart: Float,
            limitEnd: Float,
        ): Float {
            val contentSize = end - start
            val availableSize = limitEnd - limitStart
            return if (contentSize <= availableSize) {
                limitStart + (availableSize - contentSize) / 2f - start
            } else {
                when {
                    start > limitStart -> limitStart - start
                    end < limitEnd -> limitEnd - end
                    else -> 0f
                }
            }
        }

        private fun MotionEvent.center(): Pair<Float, Float> {
            var x = 0f
            var y = 0f
            for (i in 0 until pointerCount) {
                x += getX(i)
                y += getY(i)
            }
            return x / pointerCount to y / pointerCount
        }

        private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(currentZoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }

        private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (currentZoom > MIN_ZOOM) resetTransform() else zoomTo(DOUBLE_TAP_ZOOM, event.x, event.y)
                return true
            }
        }
    }
