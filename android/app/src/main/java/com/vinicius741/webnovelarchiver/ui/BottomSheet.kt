package com.vinicius741.webnovelarchiver.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Widest a sheet grows on the inner screen; it is centered beyond this. */
private const val SHEET_MAX_WIDTH_DP = 600
private const val SHEET_MAX_HEIGHT_FRACTION = 0.88f
private const val SHEET_DIM = 0.6f
private const val SHEET_ENTER_MS = 260L
private const val SHEET_EXIT_MS = 180L
private const val DRAG_DISMISS_DP = 90

/** A shown bottom sheet; [dismiss] slides it away. */
internal class BottomSheetHandle(
    val dialog: Dialog,
    private val panel: View,
) {
    private var dismissing = false

    /**
     * Closes the sheet, then runs [action] once. Further taps during the slide-out are ignored, so
     * two rows tapped in quick succession cannot both run.
     */
    fun dismissThen(action: () -> Unit) {
        if (dismissing) return
        dismiss()
        action()
    }

    fun dismiss() {
        if (!dialog.isShowing || dismissing) return
        dismissing = true
        panel
            .animate()
            .translationY(panel.height.toFloat())
            .setDuration(SHEET_EXIT_MS)
            .withEndAction { runCatching { dialog.dismiss() } }
            .start()
    }
}

/**
 * The v3 bottom sheet that replaces centered menus and pickers: a rounded panel rising from the
 * bottom edge with a grab handle, an optional [title]/[subtitle], a scrolling [body], and an
 * optional pinned [footer] (e.g. Cancel / Save). Tap outside, Back, or drag the handle down to close.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun showBottomSheet(
    context: Context,
    title: String? = null,
    subtitle: String? = null,
    body: View,
    footer: View? = null,
    onDismiss: (() -> Unit)? = null,
): BottomSheetHandle {
    val colors = ThemeManager.colors
    val radius = context.dp(ThemeManager.shapes.dialogRadius).toFloat()
    val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val panel =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(colors.elevation2, floatArrayOf(radius, radius, 0f, 0f))
            isClickable = true
        }
    val grab =
        View(context).apply {
            background = roundedBg(colors.faint, context.dp(2).toFloat())
        }
    val handleArea =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(10), 0, context.dp(4))
            addView(grab, LinearLayout.LayoutParams(context.dp(36), context.dp(4)))
        }
    panel.addView(handleArea)
    if (title != null) {
        panel.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.XL - 2), context.dp(Spacing.SM + 2), context.dp(Spacing.LG), context.dp(Spacing.SM - 2))
                addView(makeText(context, title, Type.TITLE_LARGE, colors.onSurface).apply { textSize = 19f })
                subtitle?.let { addView(makeText(context, it, Type.BODY_SMALL, colors.onSurfaceVariant)) }
            },
        )
    }
    val scroller =
        ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(context.dp(Spacing.LG), context.dp(Spacing.XS), context.dp(Spacing.LG), context.dp(Spacing.LG))
            clipToPadding = false
            addView(body)
        }
    panel.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    footer?.let {
        panel.addView(
            View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1).coerceAtLeast(1)),
        )
        panel.addView(
            it.apply { setPadding(context.dp(Spacing.LG), context.dp(Spacing.MD - 2), context.dp(Spacing.LG), context.dp(Spacing.LG)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
    // Leave room for the gesture bar so the last row / footer is reachable edge-to-edge.
    val navBarRes = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    val navBar = if (navBarRes > 0) context.resources.getDimensionPixelSize(navBarRes) else 0
    panel.setPadding(0, 0, 0, navBar)

    val handle = BottomSheetHandle(dialog, panel)
    // Drag the handle down to close.
    var downY = 0f
    handleArea.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = e.rawY
            MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (panel.translationY > context.dp(DRAG_DISMISS_DP)) {
                    handle.dismiss()
                } else {
                    panel
                        .animate()
                        .translationY(0f)
                        .setDuration(SHEET_EXIT_MS)
                        .start()
                }
        }
        true
    }

    dialog.setContentView(panel)
    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnDismissListener { onDismiss?.invoke() }
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(SHEET_DIM)
        val metrics = context.resources.displayMetrics
        val width = minOf(metrics.widthPixels, context.dp(SHEET_MAX_WIDTH_DP))
        setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    // Cap the height: measure after layout and clamp the scroller.
    panel.viewTreeObserver.addOnGlobalLayoutListener {
        val max = (context.resources.displayMetrics.heightPixels * SHEET_MAX_HEIGHT_FRACTION).toInt()
        if (panel.height > max) {
            val lp = dialog.window?.attributes ?: return@addOnGlobalLayoutListener
            if (lp.height != max) {
                lp.height = max
                dialog.window?.attributes = lp
            }
        }
    }
    dialog.show()
    panel.translationY = context.dp(400).toFloat()
    panel.post {
        panel.translationY = panel.height.toFloat()
        panel
            .animate()
            .translationY(0f)
            .setDuration(SHEET_ENTER_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }
    return handle
}

/**
 * A tappable sheet row: optional leading icon in a tinted square, a label with optional supporting
 * line, and an optional trailing check. Used by [showStyledOptionsDialog] and screens' own sheets.
 */
internal fun makeSheetRow(
    context: Context,
    label: String,
    supporting: String? = null,
    iconRes: Int = 0,
    destructive: Boolean = false,
    checked: Boolean = false,
    onClick: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val fg = if (destructive) colors.error else colors.onSurface
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(52)
        setPadding(context.dp(Spacing.SM), context.dp(Spacing.SM), context.dp(Spacing.SM), context.dp(Spacing.SM))
        isClickable = true
        isFocusable = true
        background = ripple(roundedBg(Color.TRANSPARENT, context.dp(14).toFloat()), context.dp(14).toFloat(), colors.onSurface)
        if (iconRes != 0) {
            addView(
                android.widget.ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(iconRes, if (destructive) colors.error else colors.primary))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    background = roundedBg(colors.elevation3, context.dp(12).toFloat())
                    setPadding(context.dp(Spacing.SM), context.dp(Spacing.SM), context.dp(Spacing.SM), context.dp(Spacing.SM))
                },
                LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(Spacing.MD + 2) },
            )
        }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(context, label, Type.BODY_LARGE, fg))
                supporting?.let { addView(makeText(context, it, Type.BODY_SMALL, colors.onSurfaceVariant)) }
            }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (checked) {
            addView(
                android.widget.ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(com.vinicius741.webnovelarchiver.R.drawable.wna_check, colors.primary))
                },
                LinearLayout.LayoutParams(context.dp(24), context.dp(24)),
            )
        }
        setOnClickListener { onClick() }
    }
}

/** A small uppercase group label inside a sheet. */
internal fun makeSheetSection(
    context: Context,
    label: String,
): TextView =
    makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.primary).apply {
        isAllCaps = true
        letterSpacing = 0.06f
        setPadding(context.dp(Spacing.SM), context.dp(Spacing.MD), context.dp(Spacing.SM), context.dp(Spacing.XS))
    }
