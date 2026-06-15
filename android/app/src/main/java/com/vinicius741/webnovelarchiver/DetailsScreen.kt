package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.core.EpubConfig
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showDetails(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    activeStory = story
    screen(
        title = story.title,
        subtitle = "by ${story.author}",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_more_vert, "More options") { showDetailsOverflow(story) }),
        // One scroll surface: header + actions + description + filter + chapters scroll together,
        // mirroring the RN details screen where the info panel is the FlatList header.
        scrollable = true,
    ) {
        // ---- Header (centered, mirrors RN StoryHeader) ----
        addView(buildDetailsHeader(story))

        // ---- Status / actions (mirror RN StoryActions ordering) ----
        if (story.isArchived == true) {
            addView(makeText(context, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        if (StoryActionGuards.canSync(story)) {
            fullButton("Sync Chapters", Btn.FILLED, R.drawable.wna_refresh) { syncStory(story) }
        }
        if (StoryActionGuards.canQueueDownloads(story)) {
            val remainingChapters = story.chapters.count { !it.downloaded }
            if (remainingChapters > 0) {
                val downloadLabel = if (remainingChapters == story.chapters.size) "Download All" else "Download Remaining ($remainingChapters)"
                fullButton(downloadLabel, Btn.FILLED, R.drawable.wna_download) {
                    queueDownload(story, story.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null })
                    showDetails(story.id)
                }
            }
        }
        val hasEpub = (!story.epubPaths.isNullOrEmpty()) || !story.epubPath.isNullOrBlank()
        grid(columns = 2) {
            // Generate uses the saved epubConfig (or sensible defaults) — one tap, no dialog.
            button("Generate EPUB", Btn.TONAL, R.drawable.wna_menu_book, enabled = story.downloadedChapters > 0) {
                val config = story.epubConfig ?: EpubConfig(
                    maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                    rangeStart = 1,
                    rangeEnd = story.chapters.size,
                    startAfterBookmark = false,
                )
                generateConfiguredEpub(story, config)
            }
            button("Read EPUB", Btn.OUTLINED, R.drawable.wna_book_open, enabled = hasEpub) { openEpubForStory(story) }
        }
        if (story.epubStale == true && hasEpub) {
            addView(makeText(context, "EPUB out of date", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(8))
            })
        }

        // ---- Description (inline expand/collapse, mirrors RN StoryDescription) ----
        story.description?.takeIf { it.isNotBlank() }?.let { description ->
            val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
            var expanded = false
            val descCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(4))
            }
            val descText = makeText(context, if (canExpand) truncateDescription(description) else description, Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            descCol.addView(descText)
            if (canExpand) {
                val toggle: Button = makeButton(context, "Read more", Btn.TEXT, 0) {}
                toggle.setOnClickListener {
                    expanded = !expanded
                    descText.text = if (expanded) description else truncateDescription(description)
                    toggle.text = if (expanded) "Show less" else "Read more"
                }
                descCol.addView(toggle)
            }
            descCol.addView(makeButton(context, "Copy", Btn.TEXT, R.drawable.wna_copy) {
                copyToClipboard("Story description", description)
                toast("Description copied")
            })
            addView(descCol)
        }

        // ---- Tags (new; were missing on native) ----
        story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            flow {
                tags.forEach { tag ->
                    addView(makeBadge(context, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                }
            }.apply { setPadding(0, dp(8), 0, dp(4)) }
        }

        // ---- Chapter filter (search + chips) ----
        val search = makeSearchField(context, "Search chapters")
        addView(search)
        val chipsContainer = WrapLayout(context).apply {
            horizontalSpacingDp = 8
            verticalSpacingDp = 8
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(chipsContainer)
        val chaptersContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(chaptersContainer)

        val hasBookmark = story.lastReadChapterId != null && story.chapters.any { it.id == story.lastReadChapterId }
        var chapterFilter = storage.getChapterFilterSettings().filterMode
        var chapterQuery = ""

        // Chips are rebuilt on every pick so the active one re-highlights (the original bug was
        // that chips were built once and never reflected the tapped selection).
        var pick: (String) -> Unit = {}
        pick = { mode ->
            chapterFilter = mode
            storage.saveChapterFilterSettings(ChapterFilterSettings(mode))
            renderFilterChips(chipsContainer, chapterFilter, hasBookmark, pick)
            renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                chapterQuery = s?.toString().orEmpty()
                renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderFilterChips(chipsContainer, chapterFilter, hasBookmark, pick)
        renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter)
    }
}

/**
 * Overflow menu behind the app-bar "more" icon. Holds the secondary/tertiary story actions that
 * don't warrant a primary button: opening the source site, the two advanced download-selection
 * flows, and text cleanup. Mirrors the React Native StoryMenu (Download Range / EPUB Settings /
 * Apply Text Cleanup) plus Open Source.
 */
internal fun ScreenHost.showDetailsOverflow(story: Story) {
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Open Source" to {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
            .onFailure { toast("No app available to open source") }
    }
    if (StoryActionGuards.canQueueDownloads(story)) {
        options += "Select Chapters" to { showChapterSelection(story.id) }
        options += "Download Range" to { showDownloadRangeDialog(story) }
    }
    options += "EPUB Settings" to { showEpubConfigDialog(story) }
    options += "Apply Text Cleanup" to { applyCleanup(story) }
    options += "Delete Novel" to {
        confirm("Delete \"${story.title}\"? This action cannot be undone.") {
            storage.deleteStory(story.id)
            showLibrary()
        }
    }
    AlertDialog.Builder(app)
        .setTitle("More options")
        .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.showChapterSelection(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return showDetails(story.id)
    }
    val selectedIds = mutableSetOf<String>()
    screen(title = "Select Chapters", subtitle = story.title, onBack = { showDetails(story.id) }) {
        flow {
            button("Download Selected", Btn.FILLED, R.drawable.wna_download) {
                val selectedIndexes = story.chapters.mapIndexedNotNull { index, chapter ->
                    if (selectedIds.contains(chapter.id) && !chapter.downloaded) index else null
                }
                if (selectedIndexes.isEmpty()) {
                    toast("No undownloaded chapters selected")
                } else {
                    queueDownload(story, selectedIndexes)
                    showDetails(story.id)
                }
            }
        }
        addView(scroll(LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            story.chapters.forEachIndexed { index, chapter ->
                if (!chapter.downloaded) {
                    val cb = CheckBox(app).apply {
                        text = "${index + 1}. ${chapter.title}"
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                        }
                    }
                    styledCheckBox(cb)
                    addView(cb)
                }
            }
        }), verticalFill())
    }
}

internal fun ScreenHost.renderChapterList(story: Story, list: LinearLayout, query: String, filter: String) {
    list.removeAllViews()
    val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
    val filtered = story.chapters
        .mapIndexed { index, chapter -> index to chapter }
        .filter { (_, chapter) -> chapter.title.contains(query, ignoreCase = true) }
        .filter { (index, chapter) ->
            when (filter) {
                "hideNonDownloaded" -> chapter.downloaded
                "hideAboveBookmark" -> bookmarkIndex < 0 || index >= bookmarkIndex
                else -> true
            }
        }
    if (filtered.isEmpty()) {
        list.addView(makeEmptyState(app, "No chapters match this view.", R.drawable.wna_menu_book))
        return
    }
    filtered.forEach { (index, chapter) ->
        list.addView(chapterRow(story, chapter, index, list, query, filter))
    }
}

/**
 * Compact, RN-style chapter row: status indicator + sanitized title (+ "Available Offline") with a
 * per-row overflow (⋮) for Download / Mark Read / TTS. Tapping the row opens the reader; the
 * last-read row is tinted + bold. Replaces the previous per-chapter card with a 2×2 button grid.
 */
private fun ScreenHost.chapterRow(
    story: Story,
    chapter: Chapter,
    index: Int,
    list: LinearLayout,
    query: String,
    filter: String,
): LinearLayout {
    val isLastRead = story.lastReadChapterId == chapter.id
    val radiusPx = dp(8).toFloat()
    val fill = if (isLastRead) ThemeManager.colors.primaryContainer else ThemeManager.colors.elevation1
    val row = LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(6), dp(10))
        background = ripple(roundedBg(fill, radiusPx), radiusPx, ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { showReader(story.id, chapter.id) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        }
    }
    row.addView(chapterStatusDot(chapter.downloaded).apply {
        (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, dp(10), 0)
    })
    row.addView(LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(makeText(app, "${index + 1}. ${sanitizeTitle(chapter.title)}", Type.TITLE_SMALL, if (isLastRead) ThemeManager.colors.primary else ThemeManager.colors.onSurface).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (isLastRead) setTypeface(typeface, Typeface.BOLD)
        })
        if (chapter.downloaded) {
            addView(makeText(app, "Available Offline", Type.LABEL_SMALL, ThemeManager.colors.secondary).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
    })
    row.addView(ImageView(app).apply {
        setImageDrawable(app.tintedIcon(R.drawable.wna_more_vert, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { showChapterActions(story, chapter, index, list, query, filter) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    })
    return row
}

/** Per-row overflow: Read, conditional Download, Mark as Read ⇄ Clear, Read Aloud (TTS). */
private fun ScreenHost.showChapterActions(
    story: Story,
    chapter: Chapter,
    index: Int,
    list: LinearLayout,
    query: String,
    filter: String,
) {
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Read" to { showReader(story.id, chapter.id) }
    if (!chapter.downloaded && StoryActionGuards.canQueueDownloads(story)) {
        options += "Download" to {
            queueDownload(story, listOf(index))
            showDetails(story.id)
        }
    }
    val isBookmarked = story.lastReadChapterId == chapter.id
    options += (if (isBookmarked) "Clear Bookmark" else "Mark as Read") to {
        val updated = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = true)
        storage.addOrUpdateStory(updated)
        renderChapterList(updated, list, query, filter)
    }
    options += "Read Aloud (TTS)" to { TtsForegroundService.start(app, story.id, chapter.id) }
    AlertDialog.Builder(app)
        .setTitle(sanitizeTitle(chapter.title))
        .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * Rebuilds the filter chip group so the active mode re-highlights on every pick. The "From Bookmark"
 * chip is disabled when there is no valid bookmark, mirroring RN's `disabled={!hasBookmark}`.
 */
internal fun ScreenHost.renderFilterChips(
    container: ViewGroup,
    current: String,
    hasBookmark: Boolean,
    onPick: (String) -> Unit,
) {
    container.removeAllViews()
    container.addView(makeChip(app, "All", current == "all") { onPick("all") })
    container.addView(makeChip(app, "Downloaded", current == "hideNonDownloaded") { onPick("hideNonDownloaded") })
    val bookmarkChip = makeChip(app, "From Bookmark", current == "hideAboveBookmark") { onPick("hideAboveBookmark") }
    if (!hasBookmark) {
        bookmarkChip.isEnabled = false
        bookmarkChip.alpha = 0.4f
    }
    container.addView(bookmarkChip)
}

/** Centered story header — cover, title, author, source/archived chips, and stats pills
 *  (Score / Chapters / Saved). Mirrors the RN `StoryHeader`. */
private fun ScreenHost.buildDetailsHeader(story: Story): LinearLayout {
    val col = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(8))
    }
    val cover = coverImage(story, widthDp = 150, heightDp = 225, tapToOpen = true)
    (cover.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(16))
    col.addView(cover)
    col.addView(makeText(app, story.title, Type.HEADLINE, ThemeManager.colors.onSurface).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    col.addView(makeText(app, story.author, Type.TITLE_MEDIUM, ThemeManager.colors.secondary).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(0, dp(2), 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    if (provider != null || story.isArchived == true) {
        col.flow {
            provider?.let {
                addView(makeBadge(context, it.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
            }
            if (story.isArchived == true) {
                addView(makeBadge(context, "Archived", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
            }
        }
    }
    col.flow {
        story.score?.takeIf { it.isNotBlank() }?.let {
            addView(makeStatPill(context, "Score", it))
        }
        if (story.totalChapters > 0) {
            addView(makeStatPill(context, "Chapters", story.totalChapters.toString()))
        }
        addView(makeStatPill(context, "Saved", story.downloadedChapters.toString()))
    }.apply { setPadding(0, dp(4), 0, dp(4)) }
    return col
}
