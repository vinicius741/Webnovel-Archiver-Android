package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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
    // Re-render on fold/unfold/rotation so the two-pane ↔ single-scroll layout can switch live.
    rerender = { showDetails(storyId) }
    val layout = currentScreenLayout()
    screen(
        title = story.title,
        subtitle = "by ${story.author}",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_more_vert, "More options") { showDetailsOverflow(story) }),
        // When two-pane we manage scrolling per-pane (info scrolls left, chapters scroll right), so
        // the body must NOT be wrapped in a single ScrollView. Compact mode keeps the original
        // single-surface scroll (D1) where header + chapters scroll together.
        scrollable = !layout.isTwoPane,
    ) {
        // ---- Info panel: header + actions + description + tags ----
        val infoPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        infoPanel.addView(buildDetailsHeader(story))

        if (story.isArchived == true) {
            infoPanel.addView(makeText(context, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            })
        }
        if (StoryActionGuards.canSync(story)) {
            infoPanel.addView(makeFullWidthButton(context, "Sync Chapters", Btn.FILLED, R.drawable.wna_refresh, dp(Space.SM + 2)) { syncStory(story) })
        }
        if (StoryActionGuards.canQueueDownloads(story)) {
            val remainingChapters = story.chapters.count { !it.downloaded }
            if (remainingChapters > 0) {
                val downloadLabel = if (remainingChapters == story.chapters.size) "Download All" else "Download Remaining ($remainingChapters)"
                infoPanel.addView(makeFullWidthButton(context, downloadLabel, Btn.FILLED, R.drawable.wna_download, dp(Space.SM + 2)) {
                    queueDownload(story, story.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null })
                    showDetails(story.id)
                })
            }
        }
        val hasEpub = (!story.epubPaths.isNullOrEmpty()) || !story.epubPath.isNullOrBlank()
        // D2: Generate EPUB is the primary action — promote it to a full-width button so its visual
        // weight matches its usage.
        infoPanel.addView(makeFullWidthButton(context, "Generate EPUB", Btn.TONAL, R.drawable.wna_menu_book, dp(Space.SM + 2), enabled = story.downloadedChapters > 0) {
            val config = story.epubConfig ?: EpubConfig(
                maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                rangeStart = 1,
                rangeEnd = story.chapters.size,
                startAfterBookmark = false,
            )
            generateConfiguredEpub(story, config)
        })
        // Read EPUB is now a full-width outlined button so it aligns with the other primary actions.
        infoPanel.addView(makeFullWidthButton(context, "Read EPUB", Btn.OUTLINED, R.drawable.wna_book_open, dp(Space.SM + 2), enabled = hasEpub) {
            openEpubForStory(story)
        })
        // D6: make the stale notice actionable with an inline Regenerate button.
        if (story.epubStale == true && hasEpub) {
            infoPanel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(makeText(context, "EPUB out of date", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
                addView(makeButton(context, "Regenerate", Btn.TEXT, R.drawable.wna_refresh) {
                    val config = story.epubConfig ?: EpubConfig(
                        maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                        rangeStart = 1,
                        rangeEnd = story.chapters.size,
                        startAfterBookmark = false,
                    )
                    generateConfiguredEpub(story, config)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Space.SM) })
            })
        }

        // ---- Description (inline expand/collapse, mirrors RN StoryDescription) ----
        story.description?.takeIf { it.isNotBlank() }?.let { description ->
            val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
            var expanded = false
            val descCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            }
            val descText = makeText(context, if (canExpand) truncateDescription(description) else description, Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.START
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
            infoPanel.addView(descCol)
        }

        // ---- Tags (new; were missing on native) ----
        story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            infoPanel.addView(WrapLayout(context).apply {
                horizontalSpacingDp = Space.SM
                verticalSpacingDp = Space.SM
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
                tags.forEach { tag ->
                    addView(makeBadge(context, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        // ---- Pinned chapter filter (search + chips) ----
        val chapterSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (!layout.isTwoPane) {
            // Compact: collapse toggle so the info panel can be tucked away while browsing chapters.
            var infoExpanded = true
            val collapseToggle = makeButton(context, "Hide details", Btn.TEXT, R.drawable.wna_chevron_down) {}
            collapseToggle.setOnClickListener {
                infoExpanded = !infoExpanded
                infoPanel.visibility = if (infoExpanded) View.VISIBLE else View.GONE
                collapseToggle.text = if (infoExpanded) "Hide details" else "Show details"
            }
            addView(collapseToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(Space.XS) })
            addView(infoPanel)
            chapterSection.addView(makeDivider(context))
        }
        val search = makeSearchField(context, "Search chapters")
        chapterSection.addView(search)
        val chipsContainer = WrapLayout(context).apply {
            horizontalSpacingDp = Space.SM
            verticalSpacingDp = Space.SM
            setPadding(0, dp(Space.SM), 0, dp(Space.SM))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        chapterSection.addView(chipsContainer)

        // ---- Chapter List ----
        val chaptersContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chapterSection.addView(chaptersContainer)

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

        if (layout.isTwoPane) {
            // Two-pane: info scrolls on the left, chapter filter + list scroll on the right. The info
            // pane stays pinned at a fixed width (RN StoryDetailsLayout: 280–440dp) while the chapter
            // list takes the remaining space, each with its own scroll surface. No divider is drawn
            // between the panes — a marginEnd on the info pane keeps the columns from touching.
            val leftScroll = scroll(infoPanel)
            val rightScroll = scroll(chapterSection)
            val shell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(leftScroll, LinearLayout.LayoutParams(dp(DETAILS_TWO_PANE_LEFT_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(DETAILS_TWO_PANE_GAP_DP)
                })
                addView(rightScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            addView(shell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            // Compact: chapter section flows in the same scroll surface as the info header above.
            addView(chapterSection)
        }
    }
}

/** Fixed width (dp) of the left info pane in the two-pane details layout, within the RN range. */
private const val DETAILS_TWO_PANE_LEFT_WIDTH_DP = 360

/** Gap (dp) between the info pane and the chapter list in the two-pane layout, replacing the old
 *  1dp divider with clear whitespace. */
private const val DETAILS_TWO_PANE_GAP_DP = Space.MD

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
    showStyledOptionsDialog("More options", options)
}

internal fun ScreenHost.showChapterSelection(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return showDetails(story.id)
    }
    val selectedIds = mutableSetOf<String>()
    screen(title = "Select Chapters", subtitle = story.title, onBack = { showDetails(story.id) }) {
        val downloadable = story.chapters.filter { !it.downloaded }
        // X3: select-all / deselect-all affordance for fast bulk selection.
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check) {
                selectedIds.clear()
                selectedIds.addAll(downloadable.map { it.id })
                showChapterSelection(story.id)
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close) {
                selectedIds.clear()
                showChapterSelection(story.id)
            }
        }
        addView(scroll(LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            // X1: reuse the card-style selectable row instead of bare CheckBoxes.
            downloadable.forEachIndexed { index, chapter ->
                val displayIndex = story.chapters.indexOfFirst { it.id == chapter.id } + 1
                addView(makeSelectableCardRow(
                    context,
                    title = "$displayIndex. ${sanitizeTitle(chapter.title)}",
                    subtitle = if (chapter.downloaded) "Available Offline" else null,
                    selected = selectedIds.contains(chapter.id),
                ) { checked ->
                    if (checked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                })
            }
        }), verticalFill())
        // X2: the primary bulk action is a full-width CTA docked at the bottom, next to the items.
        fullButton("Download ${selectedIds.size} Selected", Btn.FILLED, R.drawable.wna_download, bottomMarginDp = 0) {
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
    val radiusPx = dp(Space.SM).toFloat()
    val fill = if (isLastRead) ThemeManager.colors.primaryContainer else ThemeManager.colors.elevation1
    val row = LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.MD), dp(Space.SM + 2), dp(Space.XS + 2), dp(Space.SM + 2))
        background = ripple(roundedBg(fill, radiusPx), radiusPx, ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { showReader(story.id, chapter.id) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(Space.XS + 2)
        }
    }
    row.addView(chapterStatusDot(chapter.downloaded).apply {
        (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, dp(Space.SM + 2), 0)
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
        setPadding(dp(Space.SM + 2), dp(Space.SM + 2), dp(Space.SM + 2), dp(Space.SM + 2))
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
    showStyledOptionsDialog(sanitizeTitle(chapter.title), options)
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

/** Centered story header — cover, title, author, source/archived chips, and a compact
 *  "Saved / Chapters" progress summary. Mirrors the RN `StoryHeader`; D5 collapsed the three
 *  duplicate stat pills (Score/Chapters/Saved) — Score already shows in the tags row and the
 *  chapter total is implied by the list — into one progress summary. */
private fun ScreenHost.buildDetailsHeader(story: Story): LinearLayout {
    val col = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(Space.XS), 0, dp(Space.SM))
    }
    val cover = coverImage(story, widthDp = 150, heightDp = 225, tapToOpen = true)
    (cover.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(Space.LG))
    col.addView(cover)
    col.addView(makeText(app, story.title, Type.HEADLINE, ThemeManager.colors.onSurface).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    col.addView(makeText(app, story.author, Type.TITLE_MEDIUM, ThemeManager.colors.secondary).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(0, dp(2), 0, dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    if (provider != null || story.isArchived == true) {
        val badgeRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        provider?.let {
            badgeRow.addView(makeBadge(app, it.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
        }
        if (story.isArchived == true) {
            if (provider != null) {
                val spacer = View(app).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(Space.SM), 0)
                }
                badgeRow.addView(spacer)
            }
            badgeRow.addView(makeBadge(app, "Archived", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
        }
        col.addView(badgeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    if (story.totalChapters > 0) {
        col.addView(makeProgressSummary(app, story.downloadedChapters, story.totalChapters).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.XS)
                bottomMargin = dp(Space.XS)
            }
        })
    }
    return col
}
