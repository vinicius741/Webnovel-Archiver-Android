package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.window.layout.WindowMetricsCalculator
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ScreenHost
import com.vinicius741.webnovelarchiver.core.DisplayPreferences
import com.vinicius741.webnovelarchiver.core.DownloadStatus
import com.vinicius741.webnovelarchiver.core.ScreenLayout
import com.vinicius741.webnovelarchiver.core.ScreenLayoutMode
import com.vinicius741.webnovelarchiver.core.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.core.resolveScreenLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Convenience for [Context.dp] so screen code can keep writing `dp(n)`. */
internal fun ScreenHost.dp(value: Int): Int = app.dp(value)

/**
 * Captures the live window dimensions (dp) + fold sensor + user "Large Screen Layout" override into
 * a [ScreenLayout] input. Uses Jetpack WindowManager's [WindowMetricsCalculator] so the size reflects
 * the real window (incl. foldable inner display / multi-window), not the legacy display metrics.
 */
internal fun ScreenHost.screenMetrics(): ScreenLayout {
    val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(app).bounds
    val density = app.resources.displayMetrics.density
    val widthDp = ((bounds.width().toFloat()) / density.coerceAtLeast(0.001f)).toInt().coerceAtLeast(0)
    val heightDp = ((bounds.height().toFloat()) / density.coerceAtLeast(0.001f)).toInt().coerceAtLeast(0)
    val prefs: DisplayPreferences = storage.getDisplayPreferences()
    return ScreenLayout(
        widthDp = widthDp,
        heightDp = heightDp,
        hasFoldingFeature = foldTracker.isFoldingFeature.value,
        mode = ScreenLayoutMode.fromStored(prefs.screenLayoutMode),
    )
}

/** Resolves the current [ScreenLayoutResult] for the live window. Screens call this on every render. */
internal fun ScreenHost.currentScreenLayout(): ScreenLayoutResult = resolveScreenLayout(screenMetrics())

internal fun ScreenHost.scroll(child: View): ScrollView = ScrollView(app).apply {
    // Fill the allocated area when content is short; scroll when it overflows.
    isFillViewport = true
    addView(child)
}

/** MATCH_PARENT width with height 0 + weight 1, so a child fills all remaining vertical space.
 *  Use for scrolling lists placed below pinned controls so the list area never collapses to 0. */
internal fun verticalFill() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

internal fun ScreenHost.toast(message: String) =
    Toast.makeText(app, message, Toast.LENGTH_LONG).show()

internal fun ScreenHost.confirm(
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    onYes: () -> Unit,
) = AlertDialog.Builder(app).setMessage(message)
    .setPositiveButton(confirmLabel) { _, _ -> onYes() }
    .setNegativeButton(cancelLabel, null).show()

internal fun ScreenHost.prompt(title: String, value: String, onSave: (String) -> Unit) {
    val input = EditText(app).apply {
        setText(value)
        setHintTextColor(ThemeManager.colors.onSurfaceVariant)
        setTextColor(ThemeManager.colors.onSurface)
        setSingleLine()
    }
    AlertDialog.Builder(app).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }.setNegativeButton("Cancel", null).show()
}

internal fun ScreenHost.copyToClipboard(label: String, value: String) {
    val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** Returns the current clipboard text, or null when it is empty or unavailable. */
internal fun ScreenHost.clipboardText(): String? {
    val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (!clipboard.hasPrimaryClip()) return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(app)?.toString()
}

internal fun ScreenHost.loadImage(url: String, image: ImageView) {
    scope.launch {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bitmap != null) image.setImageBitmap(bitmap)
    }
}

internal fun ScreenHost.styledDialogField(value: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
    makeField(app, value, hint, inputType).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(Space.SM)
        }
    }

internal fun ScreenHost.scoreRow(score: String): View = LinearLayout(app).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(ImageView(app).apply {
        setImageDrawable(app.tintedIcon(R.drawable.wna_star, ThemeManager.colors.tertiary))
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
    })
    addView(makeText(app, score, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(dp(4), 0, 0, 0) })
}

internal fun ScreenHost.dot(color: Int): View = View(app).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
        setMargins(0, dp(6), dp(10), 0)
    }
    roundCorners(5f)
}

internal fun ScreenHost.jobStatusDot(status: String): View = dot(statusColor(status))

internal fun ScreenHost.chapterStatusDot(downloaded: Boolean): View =
    dot(if (downloaded) ThemeManager.colors.tertiary else ThemeManager.colors.outlineVariant)

internal fun statusColor(status: String): Int = when (status) {
    "completed" -> ThemeManager.colors.tertiary
    "failed", "cancelled" -> ThemeManager.colors.error
    "downloading" -> ThemeManager.colors.primary
    "paused" -> ThemeManager.colors.secondary
    else -> ThemeManager.colors.onSurfaceVariant
}

internal fun formatRelativeTime(timestamp: Long): String {
    val delta = timestamp - System.currentTimeMillis()
    if (delta <= 0L) return "now"
    val seconds = (delta / 1000L).coerceAtLeast(1L)
    return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m"
}

internal const val DESCRIPTION_PREVIEW_LENGTH = 200

internal fun truncateDescription(description: String): String {
    if (description.length <= DESCRIPTION_PREVIEW_LENGTH) return description
    val preview = description.take(DESCRIPTION_PREVIEW_LENGTH)
    val lastSpace = preview.lastIndexOf(" ")
    val trimmed = if (lastSpace > 0) preview.take(lastSpace) else preview
    return "$trimmed..."
}

private val trailingDotsRegex = Regex("\\s*(\\.{2,}|…|⋯|⋮)$")

/** Mirrors src/utils/stringUtils.ts sanitizeTitle: strip trailing ellipsis/multi-dot noise that
 *  some novel sites append to truncated list titles. */
internal fun sanitizeTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    return trailingDotsRegex.replace(title.trim(), "").trim()
}

internal fun DownloadStatus.displayName(): String = name.replace('_', ' ').lowercase()
