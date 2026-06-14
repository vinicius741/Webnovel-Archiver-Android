package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showDetails(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    activeStory = story
    screen(title = story.title, subtitle = "by ${story.author}", onBack = { showLibrary() }) {
        row {
            addView(coverImage(story, widthDp = 120, heightDp = 180, tapToOpen = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                SourceRegistry.getProvider(story.sourceUrl)?.let { provider ->
                    addView(makeBadge(context, provider.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer).apply { setPadding(0, 0, 0, 0) })
                }
                story.score?.takeIf { it.isNotBlank() }?.let {
                    addView(scoreRow(it))
                }
                if (story.totalChapters > 0) {
                    addView(makeText(context, "${story.downloadedChapters}/${story.totalChapters} downloaded", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(0, dp(4), 0, 0) })
                }
                addView(makeText(context, story.status.displayName(), Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
                if (story.epubStale == true) {
                    addView(makeBadge(context, "EPUB STALE", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer).apply { setPadding(0, dp(8), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        story.description?.takeIf { it.isNotBlank() }?.let { description ->
            addView(makeText(context, truncateDescription(description), Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            flow {
                if (description.length > DESCRIPTION_PREVIEW_LENGTH) {
                    button("Read More", Btn.TEXT) { showDescriptionDialog(story.title, description) }
                }
                button("Copy Description", Btn.TEXT, R.drawable.wna_copy) {
                    copyToClipboard("Story description", description)
                    toast("Description copied")
                }
            }
        }
        if (story.isArchived == true) {
            addView(makeText(context, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
                setPadding(0, dp(10), 0, dp(2))
            })
        }
        flow {
            button("Open Source", Btn.TONAL, R.drawable.wna_open_external) { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
            if (StoryActionGuards.canSync(story)) {
                button("Sync", Btn.TONAL, R.drawable.wna_refresh) { syncStory(story) }
            }
            if (StoryActionGuards.canQueueDownloads(story)) {
                button("Download All", Btn.FILLED, R.drawable.wna_download) { queueDownload(story, story.chapters.indices.toList()); showDetails(story.id) }
                button("Select", Btn.TEXT, R.drawable.wna_filter) { showChapterSelection(story.id) }
                button("Range", Btn.TEXT, R.drawable.wna_list) { showDownloadRangeDialog(story) }
            }
            button("Text Cleanup", Btn.TEXT, R.drawable.wna_brush) { applyCleanup(story) }
            button("EPUB", Btn.TONAL, R.drawable.wna_menu_book) { showEpubConfigDialog(story) }
            button("Open EPUB", Btn.TEXT, R.drawable.wna_book_open) { openEpubForStory(story) }
        }
        section("Chapters")
        var chapterFilter = storage.getChapterFilterSettings().filterMode
        var chapterQuery = ""
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val search = makeSearchField(context, "Search chapters")
        addView(search)
        flow {
            chip("All", chapterFilter == "all") { chapterFilter = "all"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
            chip("Downloaded", chapterFilter == "hideNonDownloaded") { chapterFilter = "hideNonDownloaded"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
            chip("From Bookmark", chapterFilter == "hideAboveBookmark") { chapterFilter = "hideAboveBookmark"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
        }
        addView(scroll(list), matchWrap())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                chapterQuery = s?.toString().orEmpty()
                renderChapterList(story, list, chapterQuery, chapterFilter)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderChapterList(story, list, chapterQuery, chapterFilter)
    }
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
        }), matchWrap())
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
        list.addView(list.card {
            row {
                addView(chapterStatusDot(chapter.downloaded))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, "${index + 1}. ${chapter.title}", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(makeText(context, if (chapter.downloaded) "Downloaded" else "Not downloaded", Type.LABEL_SMALL, if (chapter.downloaded) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (story.lastReadChapterId == chapter.id) {
                    addView(makeBadge(context, "BOOKMARK", ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
                }
            }
            flow {
                button("Read", Btn.TONAL, R.drawable.wna_book_open) { showReader(story.id, chapter.id) }
                button("Download", Btn.TEXT, R.drawable.wna_download) { queueDownload(story, listOf(index)); showDetails(story.id) }
                button("Mark Read", Btn.TEXT, R.drawable.wna_check) {
                    val updated = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = true)
                    storage.addOrUpdateStory(updated)
                    renderChapterList(updated, list, query, filter)
                }
                button("TTS", Btn.TEXT, R.drawable.wna_speaker) { TtsForegroundService.start(app, story.id, chapter.id) }
            }
        })
    }
}
