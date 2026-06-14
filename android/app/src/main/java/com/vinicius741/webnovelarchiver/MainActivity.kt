package com.vinicius741.webnovelarchiver

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vinicius741.webnovelarchiver.core.*
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.UUID
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var storage: AppStorage
    private lateinit var network: NetworkClient
    private lateinit var syncEngine: StorySyncEngine
    private lateinit var downloadEngine: DownloadEngine
    private lateinit var epubEngine: EpubEngine
    private lateinit var ttsEngine: TtsEngine
    private lateinit var frame: FrameLayout
    private var activeStory: Story? = null

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        scope.launch { toast(withContext(Dispatchers.IO) { storage.importBackupUri(uri) }); showSettings() }
    }

    private val importFullBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        scope.launch { toast(withContext(Dispatchers.IO) { storage.importFullBackupUri(uri) }); showLibrary() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = AppStorage(this)
        ThemeManager.apply(storage.getDisplayPreferences().activeThemeId)
        applyWindowTheme()
        storage.recoverInterruptedDownloads()
        network = NetworkClient()
        syncEngine = StorySyncEngine(storage, network)
        downloadEngine = DownloadEngine(storage, network)
        epubEngine = EpubEngine(storage, network)
        ttsEngine = TtsEngine(this, storage)
        downloadEngine.onChanged = { runOnUiThread { if (activeStory == null) showLibrary() else activeStory?.let { showDetails(it.id) } } }
        frame = FrameLayout(this)
        setContentView(frame)
        requestNotificationPermissionIfNeeded()
        val resumeTarget = TtsSessionPlanning.readerResumeTarget(storage.getTtsSession()) { storyId ->
            storage.getStory(storyId)
        }
        if (resumeTarget != null) {
            showReader(resumeTarget.storyId, resumeTarget.chapterId)
        } else {
            showLibrary()
        }
    }

    override fun onDestroy() {
        ttsEngine.shutdown()
        super.onDestroy()
    }

    private fun showLibrary() {
        activeStory = null
        val stories = storage.getLibrary()
        screen(
            title = "Library",
            subtitle = if (stories.isEmpty()) null else "${stories.size} novel${if (stories.size == 1) "" else "s"}",
            actions = listOf(
                AppBarAction(R.drawable.wna_globe, "Browser") { showBrowser("https://www.royalroad.com") },
                AppBarAction(R.drawable.wna_list, "Queue") { showQueue() },
                AppBarAction(R.drawable.wna_settings, "Settings") { showSettings() },
            ),
            fab = { showAddStory() },
        ) {
            if (stories.isEmpty()) {
                addView(makeEmptyState(context, "No novels yet. Tap + to add a Royal Road or Scribble Hub story.", R.drawable.wna_menu_book))
                return@screen
            }
            val tabs = storage.getTabs().sortedBy { it.order }
            val search = makeSearchField(context, "Search title or author")
            addView(search)
            var selectedTabId: String? = "__all__"
            val selectedTags = mutableSetOf<String>()
            var sortOption = "updated"
            var sortAscending = false
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val rerender = {
                renderLibraryList(stories, list, search.text.toString(), selectedTabId, selectedTags, sortOption, sortAscending)
            }
            section("Tabs")
            flow {
                chip("All", selectedTabId == "__all__") { selectedTabId = "__all__"; rerender() }
                chip("Unassigned", selectedTabId == null) { selectedTabId = null; rerender() }
                tabs.forEach { tab ->
                    chip(tab.name, selectedTabId == tab.id) { selectedTabId = tab.id; rerender() }
                }
            }
            section("Sort")
            flow {
                chip("Default", sortOption == "default") { if (sortOption == "default") sortAscending = !sortAscending else { sortOption = "default"; sortAscending = false }; rerender() }
                chip("Title", sortOption == "title") { if (sortOption == "title") sortAscending = !sortAscending else { sortOption = "title"; sortAscending = true }; rerender() }
                chip("Added", sortOption == "dateAdded") { if (sortOption == "dateAdded") sortAscending = !sortAscending else { sortOption = "dateAdded"; sortAscending = false }; rerender() }
                chip("Updated", sortOption == "lastUpdated") { if (sortOption == "lastUpdated") sortAscending = !sortAscending else { sortOption = "lastUpdated"; sortAscending = false }; rerender() }
                chip("Chapters", sortOption == "totalChapters") { if (sortOption == "totalChapters") sortAscending = !sortAscending else { sortOption = "totalChapters"; sortAscending = false }; rerender() }
                chip("Score", sortOption == "score") { if (sortOption == "score") sortAscending = !sortAscending else { sortOption = "score"; sortAscending = false }; rerender() }
                button("Select", Btn.TEXT, R.drawable.wna_check) { showLibrarySelection() }
            }
            val filterLabels = LibraryQuery.availableFilterLabels(stories, selectedTabId)
            if (filterLabels.isNotEmpty()) {
                section("Sources & Tags")
                flow {
                    filterLabels.take(10).forEach { label ->
                        chip(label, selectedTags.contains(label)) {
                            if (!selectedTags.add(label)) selectedTags.remove(label)
                            rerender()
                        }
                    }
                }
                if (filterLabels.size > 10) text("${filterLabels.size - 10} more filters available on individual stories.", Type.BODY_SMALL)
            }
            addView(scroll(list), matchWrap())
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderLibraryList(stories, list, s?.toString().orEmpty(), selectedTabId, selectedTags, sortOption, sortAscending)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            renderLibraryList(stories, list, "", selectedTabId, selectedTags, sortOption, sortAscending)
        }
    }

    private fun renderLibraryList(
        stories: List<Story>,
        list: LinearLayout,
        filter: String,
        selectedTabId: String?,
        selectedTags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ) {
        list.removeAllViews()
        val visible = LibraryQuery.filterAndSort(stories, filter, selectedTabId, selectedTags, sortOption, sortAscending)
        if (visible.isEmpty()) {
            list.addView(makeEmptyState(this, "No novels match this view.", R.drawable.wna_search))
            return
        }
        if (selectedTags.isNotEmpty()) {
            list.addView(makeText(this, "Filtered by: ${selectedTags.joinToString(", ")}", Type.LABEL_MEDIUM, ThemeManager.colors.secondary).apply {
                setPadding(dp(2), dp(10), dp(2), dp(6))
            })
        }
        visible.forEach { story ->
            list.addView(list.card {
                val content = row {
                    coverImage(story, widthDp = 72, heightDp = 108, tapToOpen = false)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeText(context, "${story.title}${if (story.isArchived == true) "" else ""}", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(makeText(context, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                            setPadding(0, dp(2), 0, 0)
                        })
                        SourceRegistry.getProvider(story.sourceUrl)?.let {
                            addView(makeText(context, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply { setPadding(0, dp(2), 0, 0) })
                        }
                        story.score?.takeIf { it.isNotBlank() }?.let { score ->
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(ImageView(context).apply {
                                    setImageDrawable(context.tintedIcon(R.drawable.wna_star, ThemeManager.colors.tertiary))
                                    layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
                                })
                                addView(makeText(context, score, Type.LABEL_LARGE, ThemeManager.colors.onSurface).apply {
                                    setPadding(dp(3), 0, 0, 0)
                                })
                            })
                        }
                        story.tags?.takeIf { it.isNotEmpty() }?.let {
                            addView(makeText(context, it.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                                setPadding(0, dp(2), 0, 0)
                            })
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (story.isArchived == true) {
                        addView(ImageView(context).apply {
                            setImageDrawable(context.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        })
                    }
                }
                content.background = selectableRipple(ThemeManager.colors.onSurface)
                content.isClickable = true
                content.setOnClickListener { showDetails(story.id) }
                if (story.totalChapters > 0) {
                    addView(makeProgress(context, story.downloadedChapters.toFloat() / story.totalChapters).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(10) }
                    })
                }
                addView(makeText(context, "${story.downloadedChapters}/${story.totalChapters} chapters • ${story.status.displayName()}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, dp(6), 0, dp(4))
                })
                flow {
                    button("Open", Btn.TEXT, R.drawable.wna_book_open) { showDetails(story.id) }
                    if (StoryActionGuards.canSync(story)) {
                        button("Sync", Btn.TONAL, R.drawable.wna_refresh) { syncStory(story) }
                    }
                    button("Move", Btn.TEXT, R.drawable.wna_folder) { showMoveStoryDialog(story) }
                    button("Delete", Btn.TEXT, R.drawable.wna_delete) { confirm("Delete ${story.title}?") { storage.deleteStory(story.id); showLibrary() } }
                }
            })
        }
    }

    private fun showLibrarySelection() {
        val stories = storage.getLibrary()
        val selectedIds = mutableSetOf<String>()
        screen(title = "Select Novels", onBack = { showLibrary() }) {
            flow {
                button("Move", Btn.TONAL, R.drawable.wna_folder) {
                    if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
                }
                button("Delete", Btn.ERROR, R.drawable.wna_delete) {
                    if (selectedIds.isEmpty()) {
                        toast("No novels selected")
                    } else {
                        confirm("Delete ${selectedIds.size} selected novels?") {
                            selectedIds.forEach { storage.deleteStory(it) }
                            showLibrary()
                        }
                    }
                }
            }
            addView(scroll(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                stories.forEach { story ->
                    val cb = CheckBox(this@MainActivity).apply {
                        text = "${story.title} - ${story.author}"
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                        }
                    }
                    styledCheckBox(cb)
                    addView(cb)
                }
            }), matchWrap())
        }
    }

    private fun showMoveStoriesDialog(storyIds: List<String>) {
        val tabs = storage.getTabs().sortedBy { it.order }
        val labels = listOf("Unassigned") + tabs.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Move ${storyIds.size} Novels")
            .setItems(labels.toTypedArray()) { _, which ->
                val tabId = tabs.getOrNull(which - 1)?.id
                storyIds.forEach { id ->
                    storage.getStory(id)?.let { story ->
                        story.tabId = tabId
                        storage.addOrUpdateStory(story)
                    }
                }
                showLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddStory() {
        val tabs = storage.getTabs().sortedBy { it.order }
        screen(title = "Add Story", subtitle = "Paste a story URL to import", onBack = { showLibrary() }) {
            val url = makeField(context, "", "Royal Road or Scribble Hub story URL", InputType.TYPE_TEXT_VARIATION_URI)
            addView(url)
            section("Save to tab")
            val tabSpinner = Spinner(context)
            val tabLabels = listOf("Unassigned") + tabs.map { it.name }
            tabSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tabLabels)
            if (tabs.isNotEmpty()) addView(tabSpinner)
            flow {
                button("Fetch Story", Btn.FILLED, R.drawable.wna_download) {
                    val tabId = tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id
                    syncStory(url.text.toString(), tabId)
                }
            }
            section("Or browse")
            flow {
                button("Royal Road", Btn.TONAL, R.drawable.wna_globe) { showBrowser("https://www.royalroad.com") }
                button("Scribble Hub", Btn.TONAL, R.drawable.wna_globe) { showBrowser("https://www.scribblehub.com") }
            }
        }
    }

    private fun showMoveStoryDialog(story: Story) {
        val tabs = storage.getTabs().sortedBy { it.order }
        val labels = listOf("Unassigned") + tabs.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Move Novel")
            .setItems(labels.toTypedArray()) { _, which ->
                story.tabId = tabs.getOrNull(which - 1)?.id
                storage.addOrUpdateStory(story)
                showLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBrowser(startUrl: String) {
        val tabs = storage.getTabs().sortedBy { it.order }
        screen(title = "Browser", subtitle = "Browse and import novels", onBack = { showLibrary() }) {
            val input = makeField(context, startUrl, "Address", InputType.TYPE_TEXT_VARIATION_URI)
            addView(input)
            val progress = makeText(context, "Ready", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(dp(2), dp(8), dp(2), dp(4))
            }
            addView(progress)
            section("Save imported novel to")
            val tabSpinner = Spinner(context)
            val tabLabels = listOf("Unassigned") + tabs.map { it.name }
            tabSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tabLabels)
            if (tabs.isNotEmpty()) addView(tabSpinner)
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val requested = request?.url?.toString() ?: return false
                    if (!isGoogleAuthUrl(requested)) return false
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requested)))
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) input.setText(url)
                    progress.text = "Loading..."
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null) input.setText(url)
                    progress.text = if (isNovelUrl(input.text.toString())) "Supported story page" else "Browse to a Royal Road fiction or Scribble Hub series page to import."
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 1..99) progress.text = "Loading $newProgress%"
                }
            }
            flow {
                button("Go", Btn.TONAL, R.drawable.wna_arrow_forward) { web.loadUrl(resolveUrl(input.text.toString())) }
                button("Back", Btn.TEXT, R.drawable.wna_arrow_back) { if (web.canGoBack()) web.goBack() else showLibrary() }
                button("Forward", Btn.TEXT, R.drawable.wna_arrow_forward) { if (web.canGoForward()) web.goForward() }
                button("Refresh", Btn.TEXT, R.drawable.wna_refresh) { web.reload() }
            }
            flow {
                button("Import", Btn.FILLED, R.drawable.wna_download) {
                    val current = input.text.toString()
                    if (!isNovelUrl(current)) {
                        toast("Open a supported story page before importing.")
                    } else {
                        syncStory(current, tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id)
                    }
                }
                button("External", Btn.TEXT, R.drawable.wna_open_external) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolveUrl(input.text.toString())))) }
                button("Home", Btn.TEXT, R.drawable.wna_list) { showLibrary() }
            }
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            web.loadUrl(resolveUrl(startUrl))
        }
    }

    private fun showDetails(storyId: String) {
        val story = storage.getStory(storyId) ?: return showLibrary()
        activeStory = story
        screen(title = story.title, subtitle = "by ${story.author}", onBack = { showLibrary() }) {
            row {
                coverImage(story, widthDp = 120, heightDp = 180, tapToOpen = true)
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
                button("Open Source", Btn.TONAL, R.drawable.wna_open_external) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
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

    private fun showChapterSelection(storyId: String) {
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
            addView(scroll(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                story.chapters.forEachIndexed { index, chapter ->
                    if (!chapter.downloaded) {
                        val cb = CheckBox(this@MainActivity).apply {
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

    private fun renderChapterList(story: Story, list: LinearLayout, query: String, filter: String) {
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
            list.addView(makeEmptyState(this, "No chapters match this view.", R.drawable.wna_menu_book))
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
                    button("TTS", Btn.TEXT, R.drawable.wna_speaker) { TtsForegroundService.start(this@MainActivity, story.id, chapter.id) }
                }
            })
        }
    }

    private fun showReader(storyId: String, chapterId: String) {
        val story = storage.getStory(storyId) ?: return
        val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
        val currentIndex = story.chapters.indexOfFirst { it.id == chapter.id }
        val readerStory = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = false)
        storage.addOrUpdateStory(readerStory)
        screen(title = chapter.title, subtitle = "${currentIndex + 1} / ${readerStory.chapters.size}", onBack = { showDetails(readerStory.id) }) {
            flow {
                button("Prev", Btn.TEXT, R.drawable.wna_skip_prev) { navigateChapter(readerStory, chapter, -1) }
                button("TTS", Btn.TONAL, R.drawable.wna_speaker) { TtsForegroundService.start(this@MainActivity, readerStory.id, chapter.id) }
                button("Pause", Btn.TEXT, R.drawable.wna_pause) { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_PAUSE) }
                button("Stop", Btn.TEXT, R.drawable.wna_stop) { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_STOP) }
                button("TTS Prev", Btn.TEXT, R.drawable.wna_skip_prev) { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_PREVIOUS) }
                button("TTS Next", Btn.TEXT, R.drawable.wna_skip_next) { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_NEXT) }
                button("Next", Btn.TEXT, R.drawable.wna_skip_next) { navigateChapter(readerStory, chapter, 1) }
            }
            val content = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
            val formattedText = TextCleanup.htmlToFormattedText(content)
            flow {
                button("Copy", Btn.TEXT, R.drawable.wna_copy) {
                    copyToClipboard("Chapter text", formattedText)
                    toast("Chapter copied")
                }
                button("Mark Read", Btn.TEXT, R.drawable.wna_check) {
                    val latest = storage.getStory(readerStory.id) ?: readerStory
                    storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = false))
                    toast("Marked as read")
                }
                button("Details", Btn.TEXT, R.drawable.wna_list) { showDetails(readerStory.id) }
            }
            val reader = WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                loadDataWithBaseURL(null, ReaderContentRenderer.document(chapter.title, content), "text/html", "utf-8", null)
            }
            addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun showQueue() {
        screen(title = "Download Manager", onBack = { showLibrary() }) {
            val queue = storage.getQueue()
            val stats = queue.groupingBy { it.status }.eachCount()
            if (queue.isNotEmpty()) {
                flow {
                    addView(makeStatPill(context, "Total", queue.size.toString()))
                    addView(makeStatPill(context, "Active", "${stats["downloading"] ?: 0}"))
                    addView(makeStatPill(context, "Queued", "${stats["pending"] ?: 0}"))
                    addView(makeStatPill(context, "Done", "${stats["completed"] ?: 0}"))
                    addView(makeStatPill(context, "Failed", "${stats["failed"] ?: 0}"))
                    addView(makeStatPill(context, "Paused", "${stats["paused"] ?: 0}"))
                }
            }
            flow {
                button("Resume", Btn.TONAL, R.drawable.wna_play) { downloadEngine.resumeAll(); DownloadForegroundService.start(this@MainActivity); showQueue() }
                button("Pause", Btn.TEXT, R.drawable.wna_pause) { downloadEngine.pauseAll(); showQueue() }
                button("Retry Failed", Btn.TEXT, R.drawable.wna_refresh) { downloadEngine.retryFailed(); DownloadForegroundService.start(this@MainActivity); showQueue() }
                button("Clear Done", Btn.TEXT, R.drawable.wna_check) { downloadEngine.clearFinished(); showQueue() }
                button("Cancel All", Btn.ERROR, R.drawable.wna_close) { confirm("Cancel all active and pending downloads?") { downloadEngine.cancelAll(); showQueue() } }
            }
            if (queue.isEmpty()) {
                addView(makeEmptyState(context, "No active downloads. Downloaded chapters will appear here.", R.drawable.wna_download))
                return@screen
            }
            val groupedScroll = scroll(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                queue.groupBy { it.storyId }
                    .values
                    .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
                    .forEach { jobs ->
                        val storyTitle = jobs.firstOrNull()?.storyTitle ?: "Unknown Story"
                        val summary = jobs.groupingBy { it.status }.eachCount()
                        addView(card {
                            addView(makeText(context, storyTitle, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface))
                            addView(makeText(context,
                                "${summary["completed"] ?: 0}/${jobs.size} chapters • ${summary["pending"] ?: 0} queued • ${summary["downloading"] ?: 0} active • ${summary["paused"] ?: 0} paused • ${summary["failed"] ?: 0} failed • ${summary["cancelled"] ?: 0} cancelled",
                                Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(4), 0, dp(6)) }
                            )
                            if (jobs.any { it.status == "pending" || it.status == "downloading" } ||
                                jobs.any { it.status == "paused" } ||
                                jobs.any { it.status == "failed" || it.status == "cancelled" }
                            ) {
                                flow {
                                    if (jobs.any { it.status == "pending" || it.status == "downloading" }) {
                                        button("Pause Story", Btn.TEXT, R.drawable.wna_pause) {
                                            jobs.filter { it.status == "pending" || it.status == "downloading" }.forEach { downloadEngine.pauseJob(it.id) }
                                            showQueue()
                                        }
                                    }
                                    if (jobs.any { it.status == "paused" }) {
                                        button("Resume Story", Btn.TONAL, R.drawable.wna_play) {
                                            jobs.filter { it.status == "paused" }.forEach { downloadEngine.resumeJob(it.id) }
                                            DownloadForegroundService.start(this@MainActivity)
                                            showQueue()
                                        }
                                    }
                                    if (jobs.any { it.status == "failed" || it.status == "cancelled" }) {
                                        button("Retry Story", Btn.TEXT, R.drawable.wna_refresh) {
                                            downloadEngine.retryFailedForStory(jobs.first().storyId)
                                            DownloadForegroundService.start(this@MainActivity)
                                            showQueue()
                                        }
                                    }
                                }
                            }
                        })
                        jobs.sortedBy { it.chapterIndex }.forEach { job ->
                            addQueueJobCard(job)
                        }
                    }
            })
            addView(groupedScroll, matchWrap())
        }
    }

    private fun LinearLayout.addQueueJobCard(job: DownloadJob) {
        addView(card {
            row {
                addView(jobStatusDot(job.status))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, "${job.chapterIndex + 1}. ${job.chapter.title}", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
                    val nextRetry = job.nextRetryAt?.let { " • next retry ${formatRelativeTime(it)}" }.orEmpty()
                    addView(makeText(context, "${job.status}${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry", Type.LABEL_SMALL, statusColor(job.status)).apply { setPadding(0, dp(2), 0, 0) })
                    job.errorCategory?.let {
                        addView(makeText(context, "Category: $it${job.errorCode?.let { code -> " ($code)" } ?: ""}", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            flow {
                if (job.status == "pending" || job.status == "downloading") {
                    button("Pause", Btn.TEXT, R.drawable.wna_pause) { downloadEngine.pauseJob(job.id); showQueue() }
                }
                if (job.status == "paused") {
                    button("Resume", Btn.TONAL, R.drawable.wna_play) { downloadEngine.resumeJob(job.id); DownloadForegroundService.start(this@MainActivity); showQueue() }
                }
                if (job.status == "pending" || job.status == "downloading" || job.status == "paused") {
                    button("Cancel", Btn.TEXT, R.drawable.wna_close) { downloadEngine.cancelJob(job.id); showQueue() }
                }
                if (job.status == "failed" || job.status == "cancelled") {
                    button("Retry", Btn.TONAL, R.drawable.wna_refresh) { downloadEngine.retryJob(job.id); DownloadForegroundService.start(this@MainActivity); showQueue() }
                }
                if (job.status in setOf("completed", "failed", "cancelled")) {
                    button("Remove", Btn.TEXT, R.drawable.wna_delete) { downloadEngine.removeJob(job.id); showQueue() }
                }
            }
        })
    }

    private fun statusColor(status: String): Int = when (status) {
        "completed" -> ThemeManager.colors.tertiary
        "failed", "cancelled" -> ThemeManager.colors.error
        "downloading" -> ThemeManager.colors.primary
        "paused" -> ThemeManager.colors.secondary
        else -> ThemeManager.colors.onSurfaceVariant
    }

    private fun jobStatusDot(status: String): View = dot(statusColor(status))

    private fun chapterStatusDot(downloaded: Boolean): View =
        dot(if (downloaded) ThemeManager.colors.tertiary else ThemeManager.colors.outlineVariant)

    private fun dot(color: Int): View = View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            setMargins(0, dp(6), dp(10), 0)
        }
        roundCorners(5f)
    }

    private fun DownloadStatus.displayName(): String = name.replace('_', ' ').lowercase()

    private fun scoreRow(score: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_star, ThemeManager.colors.tertiary))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        addView(makeText(context, score, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(dp(4), 0, 0, 0) })
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val delta = timestamp - System.currentTimeMillis()
        if (delta <= 0L) return "now"
        val seconds = (delta / 1000L).coerceAtLeast(1L)
        return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m"
    }

    private fun showSettings() {
        val settings = storage.getSettings()
        val sourceSettings = storage.getSourceDownloadSettings()
        val ttsSettings = storage.getTtsSettings()
        val displayPreferences = storage.getDisplayPreferences()
        screen(title = "Settings", onBack = { showLibrary() }) {
            section("Appearance")
            text("Theme", Type.TITLE_SMALL)
            flow {
                Themes.all.forEach { theme ->
                    chip(theme.name, displayPreferences.activeThemeId == theme.id) { saveThemePreference(theme.id) }
                }
            }
            text("Active: ${displayPreferences.activeThemeId}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
            text("Fold Layout", Type.TITLE_SMALL)
            flow {
                chip("Auto", displayPreferences.foldLayoutMode == "auto") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "auto")); showSettings() }
                chip("Cover", displayPreferences.foldLayoutMode == "cover") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "cover")); showSettings() }
                chip("Inner", displayPreferences.foldLayoutMode == "inner") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "inner")); showSettings() }
            }
            divider()
            section("Downloads")
            val concurrency = labeledField("Concurrency", settings.downloadConcurrency.toString(), InputType.TYPE_CLASS_NUMBER)
            val delay = labeledField("Delay (ms)", settings.downloadDelay.toString(), InputType.TYPE_CLASS_NUMBER)
            val maxChapters = labeledField("Max chapters per EPUB", settings.maxChaptersPerEpub.toString(), InputType.TYPE_CLASS_NUMBER)
            divider()
            section("Source Overrides")
            val sourceInputs = SourceRegistry.all().associate { provider ->
                val override = sourceSettings[provider.name]
                val sourceEnabled = CheckBox(context).apply { text = "Override ${provider.name}"; isChecked = override != null }
                var sourceConcurrency: EditText? = null
                var sourceDelay: EditText? = null
                addView(card {
                    text(provider.name, Type.TITLE_SMALL)
                    styledCheckBox(sourceEnabled)
                    addView(sourceEnabled)
                    sourceConcurrency = labeledField("${provider.name} concurrency", (override?.concurrency ?: settings.downloadConcurrency).toString(), InputType.TYPE_CLASS_NUMBER)
                    sourceDelay = labeledField("${provider.name} delay (ms)", (override?.delay ?: settings.downloadDelay).toString(), InputType.TYPE_CLASS_NUMBER)
                    flow {
                        button("Reset ${provider.name}", Btn.TEXT, R.drawable.wna_refresh) {
                            val updated = storage.getSourceDownloadSettings().toMutableMap()
                            updated.remove(provider.name)
                            storage.saveSourceDownloadSettings(updated)
                            showSettings()
                        }
                    }
                })
                provider.name to Triple(sourceEnabled, sourceConcurrency!!, sourceDelay!!)
            }
            divider()
            section("Text To Speech")
            storage.getTtsSession()?.let { session ->
                addView(card {
                    text("Saved TTS session", Type.TITLE_SMALL)
                    text("${session.chapterTitle} (chunk ${session.currentChunkIndex + 1})", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                    flow {
                        button("Resume TTS", Btn.TONAL, R.drawable.wna_play) {
                            TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_RESUME_SESSION)
                        }
                        button("Clear Session", Btn.TEXT, R.drawable.wna_delete) {
                            storage.clearTtsSession()
                            showSettings()
                        }
                    }
                })
            }
            val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            val chunkSize = labeledField("Chunk size", ttsSettings.chunkSize.toString(), InputType.TYPE_CLASS_NUMBER)
            text("Voice: ${ttsSettings.voiceIdentifier ?: "System default"}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
            flow {
                button("Save", Btn.FILLED, R.drawable.wna_check) {
                    storage.saveSettings(settings.copy(
                        downloadConcurrency = SettingsValidation.concurrency(concurrency.text.toString(), settings.downloadConcurrency),
                        downloadDelay = SettingsValidation.delay(delay.text.toString(), settings.downloadDelay),
                        maxChaptersPerEpub = SettingsValidation.maxChaptersPerEpub(maxChapters.text.toString(), settings.maxChaptersPerEpub),
                    ))
                    storage.saveSourceDownloadSettings(sourceInputs.mapNotNull { (name, inputs) ->
                        if (!inputs.first.isChecked) {
                            null
                        } else {
                            name to SourceDownloadSettings(
                                concurrency = SettingsValidation.concurrency(inputs.second.text.toString(), settings.downloadConcurrency),
                                delay = SettingsValidation.delay(inputs.third.text.toString(), settings.downloadDelay),
                            )
                        }
                    }.toMap())
                    storage.saveTtsSettings(ttsSettings.copy(
                        pitch = SettingsValidation.ttsScalar(pitch.text.toString(), ttsSettings.pitch),
                        rate = SettingsValidation.ttsScalar(rate.text.toString(), ttsSettings.rate),
                        chunkSize = SettingsValidation.ttsChunkSize(chunkSize.text.toString(), ttsSettings.chunkSize),
                    ))
                    toast("Settings saved")
                    showSettings()
                }
                button("Voice", Btn.TONAL, R.drawable.wna_speaker) { showTtsVoicePicker() }
                button("Manage Tabs", Btn.TEXT, R.drawable.wna_folder) { showTabs() }
                button("Cleanup Rules", Btn.TEXT, R.drawable.wna_brush) { showCleanupRules() }
            }
            divider()
            section("Backup")
            flow {
                button("Export JSON", Btn.TONAL, R.drawable.wna_share) { exportAndShare { storage.exportBackup() } }
                button("Import JSON", Btn.TEXT, R.drawable.wna_download) { importBackupLauncher.launch(arrayOf("application/json", "text/*")) }
                button("Full Backup", Btn.TONAL, R.drawable.wna_share) { exportAndShare { storage.exportFullBackup() } }
                button("Restore Full", Btn.TEXT, R.drawable.wna_download) { importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
            }
            flow {
                button("Clear Local Storage", Btn.ERROR, R.drawable.wna_delete) { confirm("Delete all novels, settings, and downloads?") { storage.clearAll(); showLibrary() } }
            }
        }
    }

    private fun showTtsVoicePicker() {
        val voices = ttsEngine.availableVoices()
        if (voices.isEmpty()) {
            toast("No local TTS voices available yet")
            return
        }
        val labels = listOf("System default") + voices.map { "${it.name} (${it.language})" }
        AlertDialog.Builder(this)
            .setTitle("TTS Voice")
            .setItems(labels.toTypedArray()) { _, which ->
                val current = storage.getTtsSettings()
                storage.saveTtsSettings(current.copy(voiceIdentifier = voices.getOrNull(which - 1)?.identifier))
                showSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveThemePreference(themeId: String) {
        val current = storage.getDisplayPreferences()
        storage.saveDisplayPreferences(current.copy(activeThemeId = themeId))
        applyThemePreference(themeId)
        recreate()
    }

    private fun applyThemePreference(themeId: String) {
        ThemeManager.apply(themeId)
        val nightMode = when (themeId) {
            "classic-light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun showTabs() {
        screen(title = "Manage Tabs", onBack = { showSettings() }) {
            val tabs = TabPlanning.normalizeOrders(storage.getTabs())
            row {
                val name = EditText(context).apply {
                    hint = "New tab name"
                    setBackgroundColor(Color.TRANSPARENT)
                    setHintTextColor(ThemeManager.colors.onSurfaceVariant)
                    setTextColor(ThemeManager.colors.onSurface)
                    setSingleLine()
                }
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                button("Add", Btn.TONAL, R.drawable.wna_add) {
                    val next = TabPlanning.create(
                        tabs,
                        name.text.toString(),
                        "tab_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                        System.currentTimeMillis(),
                    )
                    if (next.size > tabs.size) {
                        storage.saveTabs(next)
                        showTabs()
                    }
                }
            }
            tabs.forEachIndexed { index, tab ->
                addView(card {
                    row {
                        addView(ImageView(context).apply {
                            setImageDrawable(context.tintedIcon(R.drawable.wna_folder, ThemeManager.colors.primary))
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        })
                        addView(makeText(context, tab.name, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(makeText(context, "${storage.getLibrary().count { it.tabId == tab.id }}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant))
                    }
                    flow {
                        button("Up", Btn.TEXT, R.drawable.wna_up) {
                            if (index > 0) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index - 1))
                                showTabs()
                            }
                        }
                        button("Down", Btn.TEXT, R.drawable.wna_down) {
                            if (index < tabs.lastIndex) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index + 1))
                                showTabs()
                            }
                        }
                        button("Rename", Btn.TEXT, R.drawable.wna_edit) {
                            prompt("Rename Tab", tab.name) {
                                storage.saveTabs(TabPlanning.rename(tabs, tab.id, it))
                                showTabs()
                            }
                        }
                        button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                            confirm("Delete tab \"${tab.name}\" and move its novels to Unassigned?") {
                                storage.getLibrary().forEach { story ->
                                    if (story.tabId == tab.id) {
                                        story.tabId = null
                                        storage.addOrUpdateStory(story)
                                    }
                                }
                                storage.saveTabs(TabPlanning.delete(tabs, tab.id))
                                showTabs()
                            }
                        }
                    }
                })
            }
        }
    }

    private fun showCleanupRules() {
        screen(title = "Text Cleanup", subtitle = "Sentence removal & regex rules", onBack = { showSettings() }) {
            flow {
                button("Export JSON", Btn.TONAL, R.drawable.wna_share) { share(storage.exportCleanupRules()) }
            }
            section("Sentences")
            row {
                val sentence = EditText(context).apply {
                    hint = "Sentence to remove"
                    setBackgroundColor(Color.TRANSPARENT)
                    setHintTextColor(ThemeManager.colors.onSurfaceVariant)
                    setTextColor(ThemeManager.colors.onSurface)
                    setSingleLine()
                }
                addView(sentence, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                button("Add", Btn.TONAL, R.drawable.wna_add) {
                    val list = storage.getSentenceRemovalList()
                    val result = SentenceRemovalPlanning.save(list, sentence.text.toString())
                    if (!result.valid) {
                        toast(result.error ?: "Invalid sentence")
                    } else {
                        storage.saveSentenceRemovalList(result.sentences)
                        showCleanupRules()
                    }
                }
            }
            storage.getSentenceRemovalList().forEachIndexed { index, item ->
                addView(card {
                    text(item, Type.BODY_MEDIUM)
                    flow {
                        button("Edit", Btn.TEXT, R.drawable.wna_edit) {
                            prompt("Edit Sentence", item) { updated ->
                                val result = SentenceRemovalPlanning.save(storage.getSentenceRemovalList(), updated, index)
                                if (!result.valid) {
                                    toast(result.error ?: "Invalid sentence")
                                } else {
                                    storage.saveSentenceRemovalList(result.sentences)
                                    showCleanupRules()
                                }
                            }
                        }
                        button("Delete", Btn.TEXT, R.drawable.wna_delete) {
                            confirm("Are you sure you want to remove this sentence from the blocklist?") {
                                storage.saveSentenceRemovalList(SentenceRemovalPlanning.delete(storage.getSentenceRemovalList(), index))
                                showCleanupRules()
                            }
                        }
                    }
                })
            }
            divider()
            section("Regex Rules")
            flow {
                button("Add Regex Rule", Btn.TONAL, R.drawable.wna_add) { showRegexRuleDialog(null) }
            }
            storage.getRegexRules().forEach { rule ->
                addView(card {
                    row {
                        addView(ImageView(context).apply {
                            setImageDrawable(context.tintedIcon(if (rule.enabled) R.drawable.wna_check else R.drawable.wna_close, if (rule.enabled) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant))
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(makeText(context, rule.name, Type.TITLE_SMALL, ThemeManager.colors.onSurface))
                            addView(makeText(context, "/${rule.pattern}/${rule.flags} • ${rule.appliesTo}", Type.LABEL_SMALL, ThemeManager.colors.primary).apply { setPadding(0, dp(2), 0, 0) })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    flow {
                        button("Edit", Btn.TEXT, R.drawable.wna_edit) { showRegexRuleDialog(rule) }
                        button(if (rule.enabled) "Disable" else "Enable", Btn.TEXT) { rule.enabled = !rule.enabled; storage.saveRegexRules(storage.getRegexRules()); showCleanupRules() }
                        button("Delete", Btn.TEXT, R.drawable.wna_delete) { storage.saveRegexRules(storage.getRegexRules().filterNot { it.id == rule.id }); showCleanupRules() }
                    }
                })
            }
        }
    }

    private fun showRegexRuleDialog(existing: RegexCleanupRule?) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val name = styledDialogField(existing?.name.orEmpty(), "Rule name")
        val pattern = styledDialogField(existing?.pattern.orEmpty(), "Regex pattern")
        val flags = styledDialogField(existing?.flags ?: "i", "Flags, e.g. im")
        val appliesTo = styledDialogField(existing?.appliesTo ?: "both", "download, tts, or both")
        view.addView(name)
        view.addView(pattern)
        view.addView(flags)
        view.addView(appliesTo)
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(makeButton(this@MainActivity, "Quick Separator", Btn.TONAL, R.drawable.wna_brush) {
            showQuickRegexBuilder { generated ->
                name.setText(generated.name)
                pattern.setText(generated.pattern)
                flags.setText(generated.flags)
            }
        }) }
        view.addView(quickRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Regex Rule" else "Edit Regex Rule")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val validation = TextCleanup.validateRegexRule(
                    name.text.toString(),
                    pattern.text.toString(),
                    flags.text.toString(),
                )
                if (!validation.valid) {
                    toast(validation.error ?: "Invalid regex rule")
                    return@setOnClickListener
                }
                val normalizedTarget = when (appliesTo.text.toString().trim().lowercase()) {
                    "download", "tts" -> appliesTo.text.toString().trim().lowercase()
                    else -> "both"
                }
                val rules = storage.getRegexRules()
                if (TextCleanup.hasSimilarRegexRule(
                        rules,
                        existing?.id,
                        validation.normalizedPattern.orEmpty(),
                        validation.normalizedFlags.orEmpty(),
                        normalizedTarget,
                    )
                ) {
                    toast("A similar regex rule already exists")
                    return@setOnClickListener
                }
                val updated = RegexCleanupRule(
                    id = existing?.id ?: "rule_${System.currentTimeMillis()}",
                    name = name.text.toString().trim(),
                    pattern = validation.normalizedPattern.orEmpty(),
                    flags = validation.normalizedFlags.orEmpty(),
                    enabled = existing?.enabled ?: true,
                    appliesTo = normalizedTarget,
                )
                val index = rules.indexOfFirst { it.id == updated.id }
                if (index >= 0) rules[index] = updated else rules.add(updated)
                storage.saveRegexRules(rules)
                dialog.dismiss()
                showCleanupRules()
            }
        }
        dialog.show()
    }

    private fun showQuickRegexBuilder(onGenerated: (TextCleanup.QuickPattern) -> Unit) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val characters = styledDialogField("", "Character(s), e.g. =, -, ##")
        val minCount = styledDialogField("5", "Minimum repetitions")
        val wholeLine = CheckBox(this).apply {
            text = "Whole line only"
            isChecked = true
        }
        styledCheckBox(wholeLine)
        view.addView(characters)
        view.addView(minCount)
        view.addView(wholeLine)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Quick Separator Rule")
            .setView(view)
            .setPositiveButton("Use Pattern", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val generated = TextCleanup.generateQuickPattern(
                    characters.text.toString(),
                    minCount.text.toString().toIntOrNull() ?: 0,
                    wholeLine.isChecked,
                )
                if (generated == null) {
                    toast("Enter characters and a minimum count of at least 1")
                    return@setOnClickListener
                }
                onGenerated(generated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDownloadRangeDialog(story: Story) {
        if (!StoryActionGuards.canQueueDownloads(story)) {
            toast(StoryActionGuards.archivedActionMessage("Downloading"))
            return
        }
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val bookmarkChapterNumber = story.lastReadChapterId
            ?.let { id -> story.chapters.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Range", "Bookmark", "Count"),
            )
        }
        val start = styledDialogField("1", "Start chapter", InputType.TYPE_CLASS_NUMBER)
        val end = styledDialogField(story.chapters.size.toString(), "End chapter", InputType.TYPE_CLASS_NUMBER)
        val countStart = styledDialogField("1", "Count start chapter", InputType.TYPE_CLASS_NUMBER)
        val count = styledDialogField(DownloadRangeSelection.DEFAULT_COUNT.toString(), "Chapters to download", InputType.TYPE_CLASS_NUMBER)
        val info = makeText(this, "Total chapters: ${story.chapters.size}" + (bookmarkChapterNumber?.let { " • Bookmark at chapter $it" } ?: ""), Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, 0, 0, dp(8))
        }
        view.addView(info)
        view.addView(modeSpinner)
        view.addView(start)
        view.addView(end)
        view.addView(countStart)
        view.addView(count)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Download Range")
            .setView(view)
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

    private fun showEpubConfigDialog(story: Story) {
        if (story.chapters.isEmpty()) return toast("No chapters available")
        val current = story.epubConfig ?: EpubConfig(
            maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
            rangeStart = 1,
            rangeEnd = story.chapters.size,
            startAfterBookmark = false,
        )
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val maxChapters = styledDialogField(current.maxChaptersPerEpub.toString(), "Max chapters per EPUB", InputType.TYPE_CLASS_NUMBER)
        val rangeStart = styledDialogField(current.rangeStart.toString(), "From chapter", InputType.TYPE_CLASS_NUMBER)
        val rangeEnd = styledDialogField(current.rangeEnd.coerceAtLeast(1).coerceAtMost(story.chapters.size).toString(), "To chapter", InputType.TYPE_CLASS_NUMBER)
        val startAfterBookmark = CheckBox(this).apply {
            text = "Start after bookmark"
            isChecked = current.startAfterBookmark && story.lastReadChapterId != null
            isEnabled = story.lastReadChapterId != null
        }
        styledCheckBox(startAfterBookmark)
        view.addView(maxChapters)
        view.addView(rangeStart)
        view.addView(rangeEnd)
        view.addView(startAfterBookmark)
        view.addView(makeText(this, "Downloaded chapters: ${story.chapters.count { it.downloaded }}. EPUB generation includes only downloaded chapters in range.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("EPUB Settings")
            .setView(view)
            .setPositiveButton("Generate") { _, _ ->
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
                generateConfiguredEpub(story, config)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateConfiguredEpub(story: Story, config: EpubConfig) {
        val selectedEntries = EpubSelection.selectDownloadedChapters(story, config)
        if (selectedEntries.isEmpty()) {
            toast("No downloaded chapters in selected EPUB range")
            return
        }
        generateEpub(
            story,
            selectedEntries.map { it.chapter },
            config.maxChaptersPerEpub,
            selectedEntries.map { it.originalChapterNumber },
        )
    }

    private fun queueDownload(story: Story, indexes: List<Int>) {
        if (!StoryActionGuards.canQueueDownloads(story)) {
            toast(StoryActionGuards.archivedActionMessage("Downloading"))
            return
        }
        downloadEngine.queue(story, indexes, startNow = false)
        DownloadForegroundService.start(this)
    }

    private fun syncStory(url: String, tabId: String?) {
        if (url.isBlank()) return toast("Enter a URL")
        screen(title = "Working", onBack = null) { centerLoading("Starting...") }
        scope.launch {
            try {
                val existingBeforeSync = withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(url)?.let { provider ->
                        runCatching { storage.getStory(provider.getStoryId(url)) }.getOrNull()
                    }
                }
                val story = withContext(Dispatchers.IO) { syncEngine.fetchOrSync(url, tabId) { msg -> runOnUiThread { screen(title = "Working", onBack = null) { centerLoading(msg) } } } }
                if (existingBeforeSync != null && !story.pendingNewChapterIds.isNullOrEmpty()) {
                    val pending = story.pendingNewChapterIds.orEmpty().toSet()
                    val indexes = story.chapters.mapIndexedNotNull { index, chapter ->
                        if (chapter.id in pending && !chapter.downloaded) index else null
                    }
                    if (indexes.isNotEmpty()) {
                        queueDownload(story, indexes)
                    }
                }
                showDetails(story.id)
            } catch (error: Throwable) {
                toast(error.message ?: "Sync failed")
                showLibrary()
            }
        }
    }

    private fun syncStory(story: Story) {
        if (!StoryActionGuards.canSync(story)) {
            toast(StoryActionGuards.archivedActionMessage("Sync"))
            return
        }
        syncStory(story.sourceUrl, story.tabId)
    }

    private fun applyCleanup(story: Story) {
        scope.launch(Dispatchers.IO) {
            story.chapters.filter { it.downloaded }.forEachIndexed { _, chapter ->
                val html = storage.readChapter(chapter) ?: return@forEachIndexed
                File(chapter.filePath!!).writeText(TextCleanup.applyDownloadCleanup(html, storage.getSentenceRemovalList(), storage.getRegexRules()))
            }
            story.epubStale = true
            storage.addOrUpdateStory(story)
            withContext(Dispatchers.Main) { toast("Cleanup applied"); showDetails(story.id) }
        }
    }

    private fun generateEpub(
        story: Story,
        chapters: List<Chapter>,
        maxChaptersPerFile: Int = storage.getSettings().maxChaptersPerEpub,
        originalChapterNumbers: List<Int>? = null,
    ) {
        scope.launch {
            try {
                screen(title = "Generating EPUB", onBack = null) { centerLoading("Preparing...") }
                val results = epubEngine.generate(story, chapters, maxChaptersPerFile, originalChapterNumbers) { msg -> runOnUiThread { screen(title = "Generating EPUB", onBack = null) { centerLoading(msg) } } }
                toast("Generated ${results.size} EPUB file(s)")
                showDetails(story.id)
            } catch (error: Throwable) {
                toast(error.message ?: "EPUB failed")
                showDetails(story.id)
            }
        }
    }

    private fun navigateChapter(story: Story, chapter: Chapter, delta: Int) {
        val next = story.chapters.getOrNull(story.chapters.indexOfFirst { it.id == chapter.id } + delta) ?: return
        showReader(story.id, next.id)
    }

    private fun resolveUrl(input: String): String {
        return BrowserUrlPlanning.resolveUrl(input)
    }

    private fun isNovelUrl(url: String): Boolean {
        return SourceUrlValidation.isImportableStoryUrl(url)
    }

    private fun isGoogleAuthUrl(url: String): Boolean = BrowserUrlPlanning.isGoogleAuthUrl(url)

    private fun openFile(path: String?) {
        if (path == null) return toast("No EPUB generated")
        val file = File(path)
        if (!file.exists()) return toast("EPUB file is missing")
        val uri = fileUri(file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure { toast("No app available to open EPUB") }
    }

    private fun openEpubForStory(story: Story) {
        val paths = story.epubPaths?.filter { it.isNotBlank() }?.ifEmpty { null }
        val candidates = paths ?: story.epubPath?.let { listOf(it) }.orEmpty()
        if (candidates.isEmpty()) return toast("No EPUB generated")

        val existing = candidates.filter { File(it).exists() }
        if (existing.isEmpty()) {
            story.epubPath = null
            story.epubPaths = null
            story.epubStale = false
            storage.addOrUpdateStory(story)
            toast("EPUB file not found. Please regenerate.")
            showDetails(story.id)
            return
        }

        if (existing.size != candidates.size) {
            story.epubPaths = existing.toMutableList()
            story.epubPath = existing.firstOrNull()
            storage.addOrUpdateStory(story)
            toast("Some EPUB files were missing. ${existing.size} file(s) remain.")
        }

        if (existing.size == 1) {
            openFile(existing.first())
            return
        }

        val labels = existing.map { EpubSelection.displayNameForPath(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select EPUB to Read")
            .setItems(labels) { _, which -> openFile(existing[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share(file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(FileMimeTypes.forFilename(file.name))
            .putExtra(Intent.EXTRA_STREAM, fileUri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun exportAndShare(exporter: () -> File) {
        runCatching { share(exporter()) }
            .onFailure { toast(it.message ?: "Export failed") }
    }

    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

    /* -------------------------------------------------------------- */
    /*  Scaffold + view DSL                                            */
    /* -------------------------------------------------------------- */

    private data class AppBarAction(val icon: Int, val label: String, val onClick: () -> Unit)

    private fun screen(
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        actions: List<AppBarAction> = emptyList(),
        fab: (() -> Unit)? = null,
        block: LinearLayout.() -> Unit,
    ) {
        frame.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeManager.colors.background)
        }
        column.addView(appBar(title, subtitle, onBack, actions))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24) + systemBarBottom())
            block()
        }
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fab?.let { onClick ->
            val fabView = makeFab(this) { onClick() }
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            lp.setMargins(dp(16), dp(16), dp(16), dp(16) + systemBarBottom())
            frame.addView(fabView, lp)
        }
    }

    private fun appBar(title: String, subtitle: String?, onBack: (() -> Unit)?, actions: List<AppBarAction>): View {
        val t = ThemeManager.current
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(t.colors.elevation2)
            setPadding(dp(8), systemBarTop() + dp(8), dp(4), dp(8))
            if (onBack != null) {
                addView(iconButton(R.drawable.wna_arrow_back, "Back") { onBack() })
            } else {
                addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(12), dp(1)) })
            }
            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), 0, dp(8), 0)
            }
            titleCol.addView(makeText(this@MainActivity, title, Type.TITLE_LARGE, t.colors.onSurface).apply { includeFontPadding = false })
            subtitle?.let {
                titleCol.addView(makeText(this@MainActivity, it, Type.BODY_SMALL, t.colors.onSurfaceVariant).apply {
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.forEach { a ->
                addView(iconButton(a.icon, a.label) { a.onClick() })
            }
        }
    }

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): View {
        val t = ThemeManager.current
        val size = dp(46)
        return ImageView(this).apply {
            contentDescription = desc
            setImageDrawable(tintedIcon(iconRes, t.colors.onSurface))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = selectableRipple(t.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun makeFab(context: Context, onClick: () -> Unit): View {
        val t = ThemeManager.current
        val size = dp(56)
        return ImageView(context).apply {
            contentDescription = "Add"
            setImageDrawable(context.tintedIcon(R.drawable.wna_add, t.colors.onPrimary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ripple(roundedBg(t.colors.primary, dp(16).toFloat()), dp(16).toFloat(), t.colors.onPrimary)
            elevate(6f)
            setOnClickListener { onClick() }
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
    }

    private fun LinearLayout.centerLoading(message: String) {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
        col.addView(ProgressBar(context).apply {
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.bottomMargin = dp(16)
            layoutParams = lp
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ThemeManager.colors.primary)
        })
        col.addView(makeText(context, message, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface))
        addView(col)
    }

    private fun styledDialogField(value: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        makeField(this, value, hint, inputType)

    private fun systemBarTop(): Int {
        val res = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (res > 0) resources.getDimensionPixelSize(res) else dp(24)
    }

    private fun systemBarBottom(): Int {
        val res = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (res > 0) resources.getDimensionPixelSize(res) else 0
    }

    private fun applyWindowTheme() {
        val t = ThemeManager.current
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = t.colors.background
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !t.isDark
    }

    /* ---- container builders (operate on any ViewGroup) ---- */

    private fun ViewGroup.row(gravity: Int = Gravity.CENTER_VERTICAL, block: LinearLayout.() -> Unit): LinearLayout {
        val h = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            block()
        }
        addView(h, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return h
    }

    private fun ViewGroup.flow(spacing: Int = 8, block: WrapLayout.() -> Unit): WrapLayout {
        val f = WrapLayout(context).apply {
            horizontalSpacingDp = spacing
            verticalSpacingDp = spacing
            block()
        }
        addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return f
    }

    private fun ViewGroup.card(elevation: Int = 1, block: LinearLayout.() -> Unit): LinearLayout {
        val c = makeCard(context, elevation)
        c.block()
        c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
        return c
    }

    private fun ViewGroup.section(title: String): TextView {
        val tv = makeSectionHeader(context, title)
        addView(tv)
        return tv
    }

    private fun ViewGroup.divider() = addView(makeDivider(context))

    /* ---- leaf builders ---- */

    private fun ViewGroup.text(value: CharSequence, type: Type = Type.BODY_MEDIUM, color: Int? = null): TextView {
        val tv = makeText(context, value, type, color ?: ThemeManager.colors.onSurface)
        addView(tv)
        return tv
    }

    private fun ViewGroup.button(label: String, variant: Btn = Btn.THEME_DEFAULT, icon: Int = 0, action: () -> Unit): Button {
        val b = makeButton(context, label, variant, icon, action)
        addView(b)
        return b
    }

    private fun ViewGroup.chip(label: String, selected: Boolean = false, action: () -> Unit) {
        addView(makeChip(context, label, selected, action))
    }

    private fun ViewGroup.labeledField(label: String, value: String, inputType: Int, hint: String? = null): EditText {
        addView(makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(dp(2), dp(8), dp(2), dp(4))
        })
        val field = makeField(context, value, hint ?: label, inputType)
        addView(field)
        return field
    }

    private fun ViewGroup.coverImage(story: Story, widthDp: Int, heightDp: Int, tapToOpen: Boolean) {
        val url = story.coverUrl?.takeIf { it.isNotBlank() }
        val coverView: View = if (url == null) makeCoverPlaceholder(context, widthDp, heightDp)
        else makeCover(context, widthDp, heightDp)
        if (url != null) {
            if (tapToOpen) coverView.setOnClickListener { showCoverDialog(story) }
            loadImage(url, coverView as ImageView)
        }
        addView(coverView)
    }

    /* ---- legacy helpers kept for compatibility ---- */

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        addView(child)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun confirm(message: String, onYes: () -> Unit) = AlertDialog.Builder(this).setMessage(message).setPositiveButton("Confirm") { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    private fun prompt(title: String, value: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(value)
            setHintTextColor(ThemeManager.colors.onSurfaceVariant)
            setTextColor(ThemeManager.colors.onSurface)
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun truncateDescription(description: String): String {
        if (description.length <= DESCRIPTION_PREVIEW_LENGTH) return description
        val preview = description.take(DESCRIPTION_PREVIEW_LENGTH)
        val lastSpace = preview.lastIndexOf(" ")
        val trimmed = if (lastSpace > 0) preview.take(lastSpace) else preview
        return "$trimmed..."
    }

    private fun showDescriptionDialog(title: String, description: String) {
        val view = makeText(this, description, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll(view))
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard("Story description", description)
                toast("Description copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun showCoverDialog(story: Story) {
        val url = story.coverUrl?.takeIf { it.isNotBlank() } ?: return
        val image = ImageView(this).apply {
            contentDescription = "${story.title} cover"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ThemeManager.colors.surfaceVariant)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520))
            roundCorners(ThemeManager.shapes.dialogRadius.toFloat())
        }
        loadImage(url, image)
        AlertDialog.Builder(this)
            .setTitle(story.title)
            .setView(image)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadImage(url: String, image: ImageView) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
    }

    companion object {
        private const val DESCRIPTION_PREVIEW_LENGTH = 200
    }
}
