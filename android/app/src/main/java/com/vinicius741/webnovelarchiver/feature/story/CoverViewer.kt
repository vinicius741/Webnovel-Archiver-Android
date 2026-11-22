package com.vinicius741.webnovelarchiver.feature.story

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.BlurredBackdropView
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.ZoomableImageView
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlin.math.abs

/** One image in the cover viewer. [image] is a URL string, File, or Bitmap; [prompt] is shown on request. */
internal data class CoverViewerItem(
    val image: Any,
    val title: String,
    val subtitle: String? = null,
    val prompt: String? = null,
)

/** A button under the image; [primary] is the gold one, a disabled action reads as a state ("In use"). */
internal data class CoverViewerAction(
    val id: String,
    val label: String,
    val iconRes: Int = 0,
    val primary: Boolean = false,
    val enabled: Boolean = true,
)

/** The open viewer: [index] is the item on screen; [refresh] redraws its labels and actions after a change. */
internal interface CoverViewerHandle {
    val index: Int

    fun close()

    fun refresh()
}

/**
 * The full-screen cover viewer: the whole image as large as the screen allows (pinch or double-tap
 * to zoom), edge to edge with the system bars hidden and a blurred copy behind it. The title and
 * counter on top, the side arrows, the prompt and the caller's action buttons float over the image
 * on soft scrims instead of taking space from it; a single tap hides or shows them. Swipe sideways
 * to move between items, down to close.
 */
@SuppressLint("ClickableViewAccessibility")
internal class CoverViewer(
    private val host: ScreenHost,
    private val items: List<CoverViewerItem>,
    startIndex: Int,
    private val actions: (Int) -> List<CoverViewerAction>,
    private val onAction: (String, CoverViewerHandle) -> Unit,
) : CoverViewerHandle {
    private val context: Context = host.app
    private val dialog =
        Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    override var index: Int = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        private set
    private val backdrop = BlurredBackdropView(context, dim = Color.argb(BACKDROP_DIM_ALPHA, 0, 0, 0))
    private val image = ZoomableImageView(context)
    private val title = text(Type.TITLE_MEDIUM, Color.WHITE)
    private val subtitle = text(Type.BODY_SMALL, VIEWER_MUTED)
    private val counter = text(Type.LABEL_LARGE, VIEWER_MUTED)
    private val prev = navButton(R.drawable.wna_chevron_left, "Previous cover") { go(-1) }
    private val next = navButton(R.drawable.wna_chevron_right, "Next cover") { go(1) }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val promptToggle =
        makeButton(context, "Show prompt", Btn.TEXT, R.drawable.wna_notes) {
            promptOpen = !promptOpen
            paint()
        }
    private val promptText = text(Type.BODY_SMALL, VIEWER_MUTED).apply { setTextIsSelectable(true) }
    private val promptScroll = ScrollView(context).apply { addView(promptText) }
    private val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var promptOpen = false
    private var chromeVisible = true
    private lateinit var topBar: View
    private lateinit var bottomBar: View

    fun show() {
        dialog.setContentView(buildRoot())
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(VIEWER_BACKGROUND))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM)
            // Edge to edge with the bars hidden, so the image can use the whole screen; a swipe from
            // an edge shows them briefly.
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes =
                    attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
            WindowInsetsControllerCompat(this, decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        paint()
    }

    override fun close() {
        runCatching { dialog.dismiss() }
    }

    override fun refresh() = paint()

    private fun buildRoot(): View {
        val top =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = scrim(GradientDrawable.Orientation.TOP_BOTTOM)
                addView(navButton(R.drawable.wna_close, "Close") { close() })
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM), 0)
                        addView(title)
                        addView(subtitle)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(counter)
            }
        image.contentDescription = "Cover"
        val stage =
            object : FrameLayout(context) {
                private val swipe = GestureDetector(context, SwipeListener())

                // ZoomableImageView claims every touch, so swipes are read before it sees them.
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (!isZoomed()) swipe.onTouchEvent(ev)
                    return super.dispatchTouchEvent(ev)
                }
            }.apply {
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(prev, FrameLayout.LayoutParams(context.dp(NAV_DP), context.dp(NAV_DP), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(next, FrameLayout.LayoutParams(context.dp(NAV_DP), context.dp(NAV_DP), Gravity.END or Gravity.CENTER_VERTICAL))
            }
        promptToggle.setTextColor(ThemeManager.colors.primary)
        val bottom =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = scrim(GradientDrawable.Orientation.BOTTOM_TOP)
                addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(Spacing.LG)))
                addView(promptToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(promptScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        topBar = top
        bottomBar = bottom
        // The image gets the whole screen; the bars float over it.
        val root =
            FrameLayout(context).apply {
                setBackgroundColor(VIEWER_BACKGROUND)
                addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(
                    top,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
                )
                addView(
                    bottom,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
                )
            }
        // Keep the controls clear of the bar and cutout areas (even while the bars are hidden, since
        // they can slide back in over them); the image itself ignores insets.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars =
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val left = maxOf(bars.left, cutout.left)
            val right = maxOf(bars.right, cutout.right)
            top.setPadding(
                left + context.dp(Spacing.XS),
                maxOf(bars.top, cutout.top) + context.dp(Spacing.SM),
                right + context.dp(Spacing.LG),
                context.dp(Spacing.XL),
            )
            bottom.setPadding(
                left + context.dp(Spacing.LG),
                context.dp(Spacing.XL),
                right + context.dp(Spacing.LG),
                maxOf(bars.bottom, cutout.bottom) + context.dp(Spacing.LG),
            )
            insets
        }
        return root
    }

    /** A single tap on the image hides the bars and arrows so only the cover is left, or brings them back. */
    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        listOf(topBar, bottomBar, prev, next).forEach { view ->
            view.animate().cancel()
            if (chromeVisible) {
                if (view === prev || view === next) paintArrows() else view.visibility = View.VISIBLE
                view
                    .animate()
                    .alpha(1f)
                    .setDuration(CHROME_FADE_MS)
                    .start()
            } else {
                view
                    .animate()
                    .alpha(0f)
                    .setDuration(CHROME_FADE_MS)
                    .withEndAction { view.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun paintArrows() {
        if (!chromeVisible) return
        prev.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        next.visibility = if (index < items.lastIndex) View.VISIBLE else View.INVISIBLE
    }

    private fun scrim(orientation: GradientDrawable.Orientation) =
        GradientDrawable(orientation, intArrayOf(SCRIM_STRONG, SCRIM_SOFT, Color.TRANSPARENT))

    private fun paint() {
        val item = items.getOrNull(index) ?: return
        host.loadImage(item.image, image)
        backdrop.loadSource(item.image)
        title.text = item.title
        subtitle.text = item.subtitle.orEmpty()
        subtitle.visibility = if (item.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        counter.text = if (items.size > 1) "${index + 1} / ${items.size}" else ""
        paintArrows()
        dots.removeAllViews()
        if (items.size > 1) {
            items.indices.forEach { i ->
                val on = i == index
                dots.addView(
                    View(
                        context,
                    ).apply { background = roundedBg(if (on) ThemeManager.colors.primary else DOT_OFF, context.dp(3).toFloat()) },
                    LinearLayout.LayoutParams(context.dp(if (on) DOT_ON_DP else DOT_DP), context.dp(DOT_DP)).apply {
                        marginStart = context.dp(3)
                        marginEnd = context.dp(3)
                    },
                )
            }
        }
        val prompt = item.prompt?.takeIf { it.isNotBlank() }
        promptToggle.visibility = if (prompt != null) View.VISIBLE else View.GONE
        promptText.text = prompt.orEmpty()
        promptScroll.visibility = if (prompt != null && promptOpen) View.VISIBLE else View.GONE
        promptToggle.text = if (promptOpen) "Hide prompt" else "Show prompt"
        paintActions()
    }

    private fun paintActions() {
        actionRow.removeAllViews()
        val list = actions(index)
        actionRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEachIndexed { i, action ->
            val button: Button =
                makeButton(context, action.label, if (action.primary) Btn.FILLED else Btn.SURFACE, action.iconRes) {
                    onAction(action.id, this)
                }
            if (!action.enabled) {
                button.isEnabled = false
                button.alpha = DISABLED_ALPHA
            }
            actionRow.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = context.dp(Spacing.SM)
                    if (i > 0) marginStart = context.dp(Spacing.SM)
                },
            )
        }
    }

    private fun go(delta: Int) {
        val target = index + delta
        if (target !in items.indices) return
        index = target
        image.resetTransform()
        paint()
    }

    /** True while the image is zoomed past its fitted size, when a drag should pan rather than switch covers. */
    private fun isZoomed(): Boolean {
        val drawable = image.drawable ?: return false
        if (image.width <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return false
        val fit = minOf(image.width.toFloat() / drawable.intrinsicWidth, image.height.toFloat() / drawable.intrinsicHeight)
        val values = FloatArray(MATRIX_VALUES)
        image.imageMatrix.getValues(values)
        return values[android.graphics.Matrix.MSCALE_X] > fit * ZOOM_TOLERANCE
    }

    private inner class SwipeListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleChrome()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            return when {
                abs(dx) > context.dp(SWIPE_DP) && abs(dx) > abs(dy) -> {
                    go(if (dx < 0) 1 else -1)
                    true
                }
                dy > context.dp(SWIPE_CLOSE_DP) && abs(dy) > abs(dx) -> {
                    close()
                    true
                }
                else -> false
            }
        }
    }

    private fun text(
        type: Type,
        color: Int,
    ): TextView =
        makeText(context, "", type, color).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun navButton(
        icon: Int,
        label: String,
        onClick: () -> Unit,
    ): ImageView =
        ImageView(context).apply {
            contentDescription = label
            setImageDrawable(context.tintedIcon(icon, Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            val radius = context.dp(NAV_DP / 2).toFloat()
            background = ripple(roundedBg(NAV_BACKGROUND, radius), radius, Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(context.dp(NAV_DP), context.dp(NAV_DP))
            setOnClickListener { onClick() }
        }

    private companion object {
        val VIEWER_BACKGROUND = Color.rgb(5, 4, 3)
        val VIEWER_MUTED = Color.argb(200, 237, 230, 218)
        val NAV_BACKGROUND = Color.argb(120, 10, 8, 5)
        val DOT_OFF = Color.argb(90, 255, 255, 255)
        val SCRIM_STRONG = Color.argb(190, 0, 0, 0)
        val SCRIM_SOFT = Color.argb(110, 0, 0, 0)
        const val CHROME_FADE_MS = 180L
        const val BACKDROP_DIM_ALPHA = 190
        const val DIM = 0.85f
        const val NAV_DP = 44
        const val DOT_DP = 6
        const val DOT_ON_DP = 16
        const val SWIPE_DP = 48
        const val SWIPE_CLOSE_DP = 110
        const val MATRIX_VALUES = 9
        const val ZOOM_TOLERANCE = 1.02f
        const val DISABLED_ALPHA = 0.5f
    }
}
