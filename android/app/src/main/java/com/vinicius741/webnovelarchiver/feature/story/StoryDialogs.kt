package com.vinicius741.webnovelarchiver.feature.story

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubConfigPlanning
import com.vinicius741.webnovelarchiver.epub.EpubRangeCoverage
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.details.showStoryScreen
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.ZoomableImageView
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun ScreenHost.showEpubConfigDialog(story: Story) {
    // The details screen captures `story` once and never refreshes it, so a bookmark toggled from the
    // chapter list (which persists a fresh Story.copy) wouldn't be reflected here — leaving the
    // "Start at the bookmark" checkbox disabled even though a bookmark exists. Re-read the latest
    // persisted story so the dialog always matches on-disk state.
    val story = repository.story(story.id) ?: story
    if (story.chapters.isEmpty()) return toast("No chapters available")
    val current = EpubConfigPlanning.resolve(story, repository.settings.get().maxChaptersPerEpub)
    val hasBookmark = EpubConfigPlanning.hasBookmark(story)
    val view =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
    val maxChapters = styledDialogField(current.maxChaptersPerEpub.toString(), "Max chapters per EPUB", InputType.TYPE_CLASS_NUMBER)
    val rangeStart = styledDialogField(current.rangeStart.toString(), "From chapter", InputType.TYPE_CLASS_NUMBER)
    val rangeEnd =
        styledDialogField(
            current.rangeEnd
                .coerceAtLeast(1)
                .coerceAtMost(story.chapters.size)
                .toString(),
            "To chapter",
            InputType.TYPE_CLASS_NUMBER,
        )
    val startAtBookmark =
        CheckBox(app).apply {
            text = "Start at the bookmark"
            isChecked = current.startAtBookmark && hasBookmark
            isEnabled = hasBookmark
        }
    val restoredRangeStart = EpubSelection.rangeStartAfterDisablingBookmark(story, current)
    startAtBookmark.setOnCheckedChangeListener { _, checked ->
        if (!checked && restoredRangeStart != null && rangeStart.text.toString() == current.rangeStart.toString()) {
            rangeStart.setText(restoredRangeStart.toString())
        }
    }
    styledCheckBox(startAtBookmark)
    val chaptersOnly =
        CheckBox(app).apply {
            text = "Chapters only"
            isChecked = current.chaptersOnly
        }
    styledCheckBox(chaptersOnly)
    view.addView(maxChapters)
    view.addView(rangeStart)
    view.addView(rangeEnd)
    view.addView(startAtBookmark)
    view.addView(chaptersOnly)
    view.addView(
        makeText(
            app,
            "\"Chapters only\" omits the cover image, description/tags page, and table of contents — the EPUB contains just the chapter text.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            setPadding(0, dp(4), 0, 0)
        },
    )
    // DL2: explain why "Start at the bookmark" is disabled when there is no bookmark.
    if (!hasBookmark) {
        view.addView(
            makeText(
                app,
                "Set a bookmark by reading a chapter to enable \"Start at the bookmark.\"",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            ).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
    }
    view.addView(
        makeText(
            app,
            "Downloaded chapters: ${story.chapters.count { it.downloaded }}. EPUB generation includes only downloaded chapters in range.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            setPadding(0, dp(8), 0, 0)
        },
    )

    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("EPUB Settings")
            .setView(scroll(view))
            .setPositiveButton("Save") { _, _ ->
                val max =
                    maxChapters.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(10, 1000) ?: 150
                val start =
                    rangeStart.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(1, story.chapters.size) ?: 1
                val end =
                    rangeEnd.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(start, story.chapters.size) ?: story.chapters.size
                val config =
                    EpubConfig(
                        maxChaptersPerEpub = max,
                        rangeStart = start,
                        rangeEnd = end,
                        startAtBookmark = startAtBookmark.isChecked && hasBookmark,
                        chaptersOnly = chaptersOnly.isChecked,
                    )
                scope.launch {
                    repository.setEpubConfig(story.id, config)
                    toast("EPUB settings saved")
                    showStoryScreen(story.id)
                }
            }.setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

/**
 * Warns that the configured EPUB range includes chapters that aren't downloaded. Non-downloaded
 * chapters are silently skipped by [com.vinicius741.webnovelarchiver.epub.EpubSelection], so without
 * this gate the user could generate a partial EPUB with no notice. [onConfirm] runs only on "Generate".
 */
internal fun ScreenHost.showConfirmEpubWithMissingChaptersDialog(
    storyId: String,
    coverage: EpubRangeCoverage,
    onConfirm: () -> Unit,
) {
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Incomplete download")
            .setMessage(epubCoverageMessage(coverage))
            .setPositiveButton("Generate") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
    var displayedCoverage = coverage
    val observer =
        scope.launch {
            repository.downloadState.collect { snapshot ->
                val current = snapshot.library.firstOrNull { it.id == storyId } ?: return@collect
                val currentConfig = EpubConfigPlanning.resolve(current, repository.settings.get().maxChaptersPerEpub)
                val updated = EpubSelection.rangeCoverage(current, currentConfig)
                if (updated != displayedCoverage) {
                    displayedCoverage = updated
                    dialog.setTitle(if (updated.missing == 0) "Ready to generate" else "Incomplete download")
                    dialog.setMessage(epubCoverageMessage(updated))
                }
            }
        }
    dialog.setOnDismissListener { observer.cancel() }
}

private fun epubCoverageMessage(coverage: EpubRangeCoverage): String =
    if (coverage.missing == 0) {
        "All chapters in range are downloaded. Generate EPUB?"
    } else {
        "${coverage.missing} of ${coverage.inRange} chapter(s) in range aren't downloaded and will be skipped.\nGenerate anyway?"
    }

internal fun ScreenHost.showDescriptionDialog(
    title: String,
    description: String,
) {
    val view =
        makeText(app, description, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(title)
            .setView(scroll(view))
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard("Story description", description)
                toast("Description copied")
            }.setNegativeButton("Close", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

/**
 * Opens [story]'s displayed cover (AI when active, else the source) in the full-screen viewer. Any
 * screen can call it, e.g. a cover tapped in Downloads. [actions] add buttons under the image.
 */
internal fun ScreenHost.showCoverDialog(
    story: Story,
    actions: List<CoverViewerAction> = emptyList(),
    onAction: (String, CoverViewerHandle) -> Unit = { _, viewer -> viewer.close() },
) {
    val source: Any = activeCoverSource(story) ?: return toast("This novel has no cover image")
    showCoverViewer(listOf(CoverViewerItem(source, story.title, story.author)), actions = { actions }, onAction = onAction)
}

/** Full-screen viewer for one cover; [source] is a URL string, File, or Bitmap. */
internal fun ScreenHost.showCoverZoomDialog(
    source: Any?,
    title: String,
) {
    if (source == null) return
    showCoverViewer(listOf(CoverViewerItem(source, title)))
}

/**
 * Full-screen viewer over several covers: swipe or use the arrows to move between [items], starting
 * at [startIndex]. [actions] lists the buttons for the item on screen; [onAction] receives the
 * tapped action's id and the viewer, which can close or refresh itself.
 */
internal fun ScreenHost.showCoverViewer(
    items: List<CoverViewerItem>,
    startIndex: Int = 0,
    actions: (Int) -> List<CoverViewerAction> = { emptyList() },
    onAction: (String, CoverViewerHandle) -> Unit = { _, viewer -> viewer.close() },
): CoverViewerHandle? {
    if (items.isEmpty()) return null
    return CoverViewer(this, items, startIndex, actions, onAction).also { it.show() }
}
