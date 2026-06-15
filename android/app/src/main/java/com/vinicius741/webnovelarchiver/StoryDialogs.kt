package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import com.vinicius741.webnovelarchiver.core.DownloadRangeSelection
import com.vinicius741.webnovelarchiver.core.EpubConfig
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showDownloadRangeDialog(story: Story) {
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return
    }
    val view = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
    val bookmarkChapterNumber = story.lastReadChapterId
        ?.let { id -> story.chapters.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?.plus(1)
    val modeSpinner = Spinner(app).apply {
        adapter = ArrayAdapter(
            app,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Range", "Bookmark", "Count"),
        )
    }
    val start = styledDialogField("1", "Start chapter", InputType.TYPE_CLASS_NUMBER)
    val end = styledDialogField(story.chapters.size.toString(), "End chapter", InputType.TYPE_CLASS_NUMBER)
    val countStart = styledDialogField("1", "Count start chapter", InputType.TYPE_CLASS_NUMBER)
    val count = styledDialogField(DownloadRangeSelection.DEFAULT_COUNT.toString(), "Chapters to download", InputType.TYPE_CLASS_NUMBER)
    val info = makeText(app, "Total chapters: ${story.chapters.size}" + (bookmarkChapterNumber?.let { " • Bookmark at chapter $it" } ?: ""), Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(0, 0, 0, dp(8))
    }
    view.addView(info)
    view.addView(modeSpinner)
    view.addView(start)
    view.addView(end)
    view.addView(countStart)
    view.addView(count)
    val dialog = AlertDialog.Builder(app)
        .setTitle("Download Range")
        .setView(scroll(view))
        .setPositiveButton("Download", null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mode = when (modeSpinner.selectedItemPosition) {
                1 -> DownloadRangeSelection.Mode.BOOKMARK
                2 -> DownloadRangeSelection.Mode.COUNT
                else -> DownloadRangeSelection.Mode.RANGE
            }
            val selection = DownloadRangeSelection.select(
                mode = mode,
                totalChapters = story.chapters.size,
                rangeStart = start.text.toString().toIntOrNull(),
                rangeEnd = end.text.toString().toIntOrNull(),
                countStart = countStart.text.toString().toIntOrNull(),
                count = count.text.toString().toIntOrNull(),
                bookmarkChapterNumber = bookmarkChapterNumber,
            )
            if (!selection.valid) {
                toast(selection.error ?: "Please enter a valid range of chapters.")
                return@setOnClickListener
            }
            queueDownload(story, selection.indexes)
            dialog.dismiss()
            showDetails(story.id)
        }
    }
    dialog.show()
}

internal fun ScreenHost.showEpubConfigDialog(story: Story) {
    if (story.chapters.isEmpty()) return toast("No chapters available")
    val current = story.epubConfig ?: EpubConfig(
        maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
        rangeStart = 1,
        rangeEnd = story.chapters.size,
        startAfterBookmark = false,
    )
    val view = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    val maxChapters = styledDialogField(current.maxChaptersPerEpub.toString(), "Max chapters per EPUB", InputType.TYPE_CLASS_NUMBER)
    val rangeStart = styledDialogField(current.rangeStart.toString(), "From chapter", InputType.TYPE_CLASS_NUMBER)
    val rangeEnd = styledDialogField(current.rangeEnd.coerceAtLeast(1).coerceAtMost(story.chapters.size).toString(), "To chapter", InputType.TYPE_CLASS_NUMBER)
    val startAfterBookmark = CheckBox(app).apply {
        text = "Start after bookmark"
        isChecked = current.startAfterBookmark && story.lastReadChapterId != null
        isEnabled = story.lastReadChapterId != null
    }
    styledCheckBox(startAfterBookmark)
    view.addView(maxChapters)
    view.addView(rangeStart)
    view.addView(rangeEnd)
    view.addView(startAfterBookmark)
    view.addView(makeText(app, "Downloaded chapters: ${story.chapters.count { it.downloaded }}. EPUB generation includes only downloaded chapters in range.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(0, dp(8), 0, 0)
    })

    AlertDialog.Builder(app)
        .setTitle("EPUB Settings")
        .setView(scroll(view))
        .setPositiveButton("Save") { _, _ ->
            val max = maxChapters.text.toString().toIntOrNull()?.coerceIn(10, 1000) ?: 150
            val start = rangeStart.text.toString().toIntOrNull()?.coerceIn(1, story.chapters.size) ?: 1
            val end = rangeEnd.text.toString().toIntOrNull()?.coerceIn(start, story.chapters.size) ?: story.chapters.size
            val config = EpubConfig(
                maxChaptersPerEpub = max,
                rangeStart = start,
                rangeEnd = end,
                startAfterBookmark = startAfterBookmark.isChecked && story.lastReadChapterId != null,
            )
            story.epubConfig = config
            storage.addOrUpdateStory(story)
            toast("EPUB settings saved")
            showDetails(story.id)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.showDescriptionDialog(title: String, description: String) {
    val view = makeText(app, description, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }
    AlertDialog.Builder(app)
        .setTitle(title)
        .setView(scroll(view))
        .setPositiveButton("Copy") { _, _ ->
            copyToClipboard("Story description", description)
            toast("Description copied")
        }
        .setNegativeButton("Close", null)
        .show()
}

internal fun ScreenHost.showCoverDialog(story: Story) {
    val url = story.coverUrl?.takeIf { it.isNotBlank() } ?: return
    val image = ImageView(app).apply {
        contentDescription = "${story.title} cover"
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(ThemeManager.colors.surfaceVariant)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520))
        roundCorners(ThemeManager.shapes.dialogRadius.toFloat())
    }
    loadImage(url, image)
    AlertDialog.Builder(app)
        .setTitle(story.title)
        .setView(image)
        .setNegativeButton("Close", null)
        .show()
}
