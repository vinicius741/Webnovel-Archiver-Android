package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import coil3.load
import coil3.request.crossfade
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayout
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutMode
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.ui.layout.resolveScreenLayout
import java.util.Locale

internal fun ScreenHost.dp(value: Int): Int = app.dp(value)

/**
 * Window dims (dp) + fold sensor + user layout override. Uses WindowManager metrics so the size
 * reflects the real window (foldables, multi-window), not legacy display metrics.
 */
internal fun ScreenHost.screenMetrics(): ScreenLayout {
    val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(app).bounds
    val density = app.resources.displayMetrics.density
    val widthDp = ((bounds.width().toFloat()) / density.coerceAtLeast(0.001f)).toInt().coerceAtLeast(0)
    val heightDp = ((bounds.height().toFloat()) / density.coerceAtLeast(0.001f)).toInt().coerceAtLeast(0)
    val prefs: DisplayPreferences = repository.getDisplayPreferences()
    return ScreenLayout(
        widthDp = widthDp,
        heightDp = heightDp,
        hasFoldingFeature = foldTracker.isFoldingFeature.value,
        mode = ScreenLayoutMode.fromStored(prefs.screenLayoutMode),
    )
}

/** Resolves the current [ScreenLayoutResult] for the live window. Screens call this on every render. */
internal fun ScreenHost.currentScreenLayout(): ScreenLayoutResult = resolveScreenLayout(screenMetrics())

internal fun ScreenHost.scroll(child: View): ScrollView =
    ScrollView(app).apply {
        // Fill the viewport when short; scroll when overflowing.
        isFillViewport = true
        addView(child)
    }

/** MATCH_PARENT width, height 0 + weight 1: child fills remaining vertical space below pinned
 *  controls so a scrolling list area never collapses to 0. */
internal fun verticalFill() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

internal fun ScreenHost.toast(message: String) = Toast.makeText(app, message, Toast.LENGTH_LONG).show()

internal fun ScreenHost.confirm(
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    onYes: () -> Unit,
) = AlertDialog
    .Builder(app)
    .setMessage(message)
    .setPositiveButton(confirmLabel) { _, _ -> onYes() }
    .setNegativeButton(cancelLabel, null)
    .show()
    .also { it.applyAppTheme() }

internal fun ScreenHost.alert(
    title: String,
    message: String,
) = AlertDialog
    .Builder(app)
    .setTitle(title)
    .setMessage(message)
    .setPositiveButton("OK", null)
    .show()
    .also { it.applyAppTheme() }

internal fun ScreenHost.prompt(
    title: String,
    value: String,
    onSave: (String) -> Unit,
) {
    val input = makeField(app, value, title, InputType.TYPE_CLASS_TEXT)
    val container =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(Space.SM), app.dp(24), 0)
            addView(input)
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

internal fun ScreenHost.copyToClipboard(
    label: String,
    value: String,
) {
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

internal fun ScreenHost.loadImage(
    source: Any?,
    image: ImageView,
) {
    // Coil gives cover loads caching, downsampling, view-detach cancellation, and placeholder/error
    // drawables. `source` is a remote URL or a local java.io.File (generated AI cover).
    image.load(source) {
        crossfade(true)
    }
}

internal fun ScreenHost.styledDialogField(
    value: String,
    hint: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
): EditText =
    makeField(app, value, hint, inputType).apply {
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.SM)
            }
    }

// Providers store scores inconsistently (Royal Road "4.84 / 5", Scribble Hub "4.8"); reformat to a
// canonical two-decimal US-locale value. The " / 5" is dropped — the star glyph already conveys it.
// Normalize here, not at the provider layer, so already-stored stories pick it up without re-sync.
internal fun formatScore(score: String): String {
    val value =
        Regex("""(\d+(?:\.\d+)?)""")
            .find(score)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?: return score.trim()
    return String.format(Locale.US, "%.2f", value)
}

internal fun ScreenHost.scoreRow(
    score: String,
    iconSizeDp: Int = 16,
    ratingCount: Long? = null,
    trailing: View? = null,
): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_star, ThemeManager.colors.tertiary))
                layoutParams = LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp))
            },
        )
        addView(makeText(app, formatScore(score), Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(dp(4), 0, 0, 0) })
        ratingCount?.let { count ->
            addView(
                makeText(
                    app,
                    "· ${formatSourceCount(count)} ratings",
                    Type.LABEL_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                ).apply { setPadding(dp(Space.SM), 0, 0, 0) },
            )
        }
        trailing?.let { addView(it) }
    }

internal fun formatSourceCount(value: Long): String =
    java.text.NumberFormat
        .getIntegerInstance(java.util.Locale.US)
        .format(value)

internal fun ScreenHost.dot(color: Int): View =
    View(app).apply {
        setBackgroundColor(color)
        layoutParams =
            LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                setMargins(0, dp(6), dp(10), 0)
            }
        roundCorners(5f)
    }

internal fun ScreenHost.jobStatusDot(status: String): View = dot(statusColor(status))

internal fun ScreenHost.chapterStatusDot(downloaded: Boolean): View =
    dot(if (downloaded) ThemeManager.colors.tertiary else ThemeManager.colors.outlineVariant)

internal fun statusColor(status: String): Int {
    if (status !in DownloadJobStatus.wires) return ThemeManager.colors.onSurfaceVariant
    return when (DownloadJobStatus.parse(status)) {
        DownloadJobStatus.Completed -> ThemeManager.colors.tertiary
        DownloadJobStatus.Failed, DownloadJobStatus.Cancelled -> ThemeManager.colors.error
        DownloadJobStatus.Downloading -> ThemeManager.colors.primary
        DownloadJobStatus.Paused -> ThemeManager.colors.secondary
        DownloadJobStatus.Pending -> ThemeManager.colors.onSurfaceVariant
    }
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
    // Collapse paragraphs to one line for the collapsed teaser; expanded keeps the original breaks.
    val flat = description.replace(Regex("\\s+"), " ").trim()
    val preview = flat.take(DESCRIPTION_PREVIEW_LENGTH)
    val lastSpace = preview.lastIndexOf(" ")
    val trimmed = if (lastSpace > 0) preview.take(lastSpace) else preview
    return "$trimmed..."
}

private val trailingDotsRegex = Regex("\\s*(\\.{2,}|…|⋯|⋮)$")

/** Strips trailing ellipsis/multi-dot noise some novel sites append to truncated list titles. */
internal fun sanitizeTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    return trailingDotsRegex.replace(title.trim(), "").trim()
}

internal fun DownloadStatus.displayName(): String = name.replace('_', ' ').lowercase()
