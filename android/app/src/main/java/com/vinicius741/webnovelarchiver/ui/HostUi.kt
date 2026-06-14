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
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ScreenHost
import com.vinicius741.webnovelarchiver.core.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Convenience for [Context.dp] so screen code can keep writing `dp(n)`. */
internal fun ScreenHost.dp(value: Int): Int = app.dp(value)

internal fun ScreenHost.scroll(child: View): ScrollView = ScrollView(app).apply { addView(child) }

internal fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal fun ScreenHost.toast(message: String) =
    Toast.makeText(app, message, Toast.LENGTH_LONG).show()

internal fun ScreenHost.confirm(message: String, onYes: () -> Unit) =
    AlertDialog.Builder(app).setMessage(message).setPositiveButton("Confirm") { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()

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
    makeField(app, value, hint, inputType)

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

internal fun DownloadStatus.displayName(): String = name.replace('_', ' ').lowercase()
