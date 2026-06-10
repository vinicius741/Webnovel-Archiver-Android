package com.vinicius741.webnovelarchiver

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.Editable
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
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.core.*
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
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
    private lateinit var root: LinearLayout
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
        applyThemePreference(storage.getDisplayPreferences().activeThemeId)
        storage.recoverInterruptedDownloads()
        network = NetworkClient()
        syncEngine = StorySyncEngine(storage, network)
        downloadEngine = DownloadEngine(storage, network)
        epubEngine = EpubEngine(storage, network)
        ttsEngine = TtsEngine(this, storage)
        downloadEngine.onChanged = { runOnUiThread { if (activeStory == null) showLibrary() else activeStory?.let { showDetails(it.id) } } }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        setContentView(root)
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
        screen("Library") {
            row {
                button("Add") { showAddStory() }
                button("Browser") { showBrowser("https://www.royalroad.com") }
                button("Queue") { showQueue() }
                button("Settings") { showSettings() }
            }
            val tabs = storage.getTabs().sortedBy { it.order }
            if (stories.isEmpty()) {
                text("No novels yet. Add a Royal Road or Scribble Hub story URL.")
            } else {
                val search = EditText(this@MainActivity).apply { hint = "Search title or author" }
                addView(search)
                var selectedTabId: String? = "__all__"
                val selectedTags = mutableSetOf<String>()
                var sortOption = "updated"
                var sortAscending = false
                val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val rerender = {
                    renderLibraryList(stories, list, search.text.toString(), selectedTabId, selectedTags, sortOption, sortAscending)
                }
                row {
                    button("All") { selectedTabId = "__all__"; rerender() }
                    button("Unassigned") { selectedTabId = null; rerender() }
                    tabs.forEach { tab ->
                        button(tab.name) { selectedTabId = tab.id; rerender() }
                    }
                }
                row {
                    button("Default") { if (sortOption == "default") sortAscending = !sortAscending else { sortOption = "default"; sortAscending = false }; rerender() }
                    button("Title") { if (sortOption == "title") sortAscending = !sortAscending else { sortOption = "title"; sortAscending = true }; rerender() }
                    button("Added") { if (sortOption == "dateAdded") sortAscending = !sortAscending else { sortOption = "dateAdded"; sortAscending = false }; rerender() }
                    button("Updated") { if (sortOption == "lastUpdated") sortAscending = !sortAscending else { sortOption = "lastUpdated"; sortAscending = false }; rerender() }
                }
                row {
                    button("Chapters") { if (sortOption == "totalChapters") sortAscending = !sortAscending else { sortOption = "totalChapters"; sortAscending = false }; rerender() }
                    button("Score") { if (sortOption == "score") sortAscending = !sortAscending else { sortOption = "score"; sortAscending = false }; rerender() }
                    button("Select") { showLibrarySelection() }
                }
                val filterLabels = LibraryQuery.availableFilterLabels(stories, selectedTabId)
                if (filterLabels.isNotEmpty()) {
                    text("Sources and Tags")
                    row {
                        filterLabels.take(8).forEach { label ->
                            button(label) {
                                if (!selectedTags.add(label)) selectedTags.remove(label)
                                rerender()
                            }
                        }
                    }
                    if (filterLabels.size > 8) text("${filterLabels.size - 8} more filters available on individual stories.")
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
            list.addView(TextView(this).apply { text = "No novels match this view."; setPadding(4, 12, 4, 12) })
            return
        }
        if (selectedTags.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "Filtered by: ${selectedTags.joinToString(", ")}"
                setPadding(4, 8, 4, 8)
            })
        }
        visible.forEach { story ->
            list.addView(card {
                row {
                    coverImage(story, widthDp = 72, heightDp = 108, tapToOpen = false)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        text("${story.title} ${if (story.isArchived == true) "(Archive)" else ""}", 18)
                        text("by ${story.author}")
                        text("${story.downloadedChapters}/${story.totalChapters} chapters • ${story.status}")
                        story.tags?.takeIf { it.isNotEmpty() }?.let { text(it.take(5).joinToString(", ")) }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                row {
                    button("Open") { showDetails(story.id) }
                    if (StoryActionGuards.canSync(story)) {
                        button("Sync") { syncStory(story) }
                    }
                    button("Move") { showMoveStoryDialog(story) }
                    button("Delete") { confirm("Delete ${story.title}?") { storage.deleteStory(story.id); showLibrary() } }
                }
            })
        }
    }

    private fun showLibrarySelection() {
        val stories = storage.getLibrary()
        val selectedIds = mutableSetOf<String>()
        screen("Select Novels") {
            row {
                button("Move") {
                    if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
                }
                button("Delete") {
                    if (selectedIds.isEmpty()) {
                        toast("No novels selected")
                    } else {
                        confirm("Delete ${selectedIds.size} selected novels?") {
                            selectedIds.forEach { storage.deleteStory(it) }
                            showLibrary()
                        }
                    }
                }
                button("Back") { showLibrary() }
            }
            addView(scroll(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                stories.forEach { story ->
                    addView(CheckBox(this@MainActivity).apply {
                        text = "${story.title} - ${story.author}"
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                        }
                    })
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
        screen("Add Story") {
            val url = EditText(this@MainActivity).apply { hint = "Royal Road or Scribble Hub story URL"; setSingleLine() }
            addView(url)
            val tabSpinner = Spinner(this@MainActivity)
            val tabLabels = listOf("Unassigned") + tabs.map { it.name }
            tabSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tabLabels)
            if (tabs.isNotEmpty()) addView(tabSpinner)
            row {
                button("Fetch Story") {
                    val tabId = tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id
                    syncStory(url.text.toString(), tabId)
                }
                button("Royal Road") { showBrowser("https://www.royalroad.com") }
                button("Scribble Hub") { showBrowser("https://www.scribblehub.com") }
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
        screen("Browser") {
            val input = EditText(this@MainActivity).apply { setText(startUrl); setSingleLine() }
            addView(input)
            val progress = TextView(this@MainActivity).apply {
                text = "Ready"
                setPadding(4, 4, 4, 4)
            }
            addView(progress)
            val tabSpinner = Spinner(this@MainActivity)
            val tabLabels = listOf("Unassigned") + tabs.map { it.name }
            tabSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tabLabels)
            if (tabs.isNotEmpty()) addView(tabSpinner)
            val web = WebView(this@MainActivity)
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
            row {
                button("Go") { web.loadUrl(resolveUrl(input.text.toString())) }
                button("Back") { if (web.canGoBack()) web.goBack() else showLibrary() }
                button("Forward") { if (web.canGoForward()) web.goForward() }
                button("Refresh") { web.reload() }
            }
            row {
                button("Import") {
                    val current = input.text.toString()
                    if (!isNovelUrl(current)) {
                        toast("Open a supported story page before importing.")
                    } else {
                        syncStory(current, tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id)
                    }
                }
                button("External") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolveUrl(input.text.toString())))) }
                button("Home") { showLibrary() }
            }
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            web.loadUrl(resolveUrl(startUrl))
        }
    }

    private fun showDetails(storyId: String) {
        val story = storage.getStory(storyId) ?: return showLibrary()
        activeStory = story
        screen(story.title) {
            coverImage(story, widthDp = 128, heightDp = 192, tapToOpen = true)
            text("by ${story.author}", 16)
            SourceRegistry.getProvider(story.sourceUrl)?.let { text("Source: ${it.name}") }
            story.score?.let { text("Score: $it") }
            story.description?.takeIf { it.isNotBlank() }?.let { description ->
                text(truncateDescription(description))
                row {
                    if (description.length > DESCRIPTION_PREVIEW_LENGTH) {
                        button("Read More") { showDescriptionDialog(story.title, description) }
                    }
                    button("Copy Description") {
                        copyToClipboard("Story description", description)
                        toast("Description copied")
                    }
                }
            }
            text("${story.downloadedChapters}/${story.totalChapters} downloaded • ${story.status}${if (story.epubStale == true) " • EPUB stale" else ""}")
            if (story.isArchived == true) {
                text("Archived snapshot: sync and downloads disabled")
            }
            row {
                button("Open Source") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
                if (StoryActionGuards.canSync(story)) {
                    button("Sync") { syncStory(story) }
                }
                if (StoryActionGuards.canQueueDownloads(story)) {
                    button("Download All") { queueDownload(story, story.chapters.indices.toList()); showDetails(story.id) }
                    button("Select") { showChapterSelection(story.id) }
                }
                button("Text Cleanup") { applyCleanup(story) }
                button("EPUB") { showEpubConfigDialog(story) }
            }
            row {
                if (StoryActionGuards.canQueueDownloads(story)) {
                    button("Range") { showDownloadRangeDialog(story) }
                }
                button("Open EPUB") { openEpubForStory(story) }
                button("Back") { showLibrary() }
            }
            var chapterFilter = storage.getChapterFilterSettings().filterMode
            var chapterQuery = ""
            val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val search = EditText(this@MainActivity).apply {
                hint = "Search chapters"
                setSingleLine()
            }
            addView(search)
            row {
                button("All") { chapterFilter = "all"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
                button("Downloaded") { chapterFilter = "hideNonDownloaded"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
                button("From Bookmark") { chapterFilter = "hideAboveBookmark"; storage.saveChapterFilterSettings(ChapterFilterSettings(chapterFilter)); renderChapterList(story, list, chapterQuery, chapterFilter) }
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
        screen("Select Chapters") {
            text(story.title, 18)
            row {
                button("Download Selected") {
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
                button("Back") { showDetails(story.id) }
            }
            addView(scroll(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                story.chapters.forEachIndexed { index, chapter ->
                    if (!chapter.downloaded) {
                        addView(CheckBox(this@MainActivity).apply {
                            text = "${index + 1}. ${chapter.title}"
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                            }
                        })
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
            list.addView(TextView(this).apply {
                text = "No chapters match this view."
                setPadding(4, 12, 4, 12)
            })
            return
        }
        filtered.forEach { (index, chapter) ->
            list.addView(card {
                text("${index + 1}. ${chapter.title}${if (story.lastReadChapterId == chapter.id) " • Bookmark" else ""}")
                text(if (chapter.downloaded) "Downloaded" else "Not downloaded")
                row {
                    button("Read") { showReader(story.id, chapter.id) }
                    button("Download") { queueDownload(story, listOf(index)); showDetails(story.id) }
                    button("Mark Read") {
                        val updated = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = true)
                        storage.addOrUpdateStory(updated)
                        renderChapterList(updated, list, query, filter)
                    }
                    button("TTS") { TtsForegroundService.start(this@MainActivity, story.id, chapter.id) }
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
        screen(chapter.title) {
            text("${currentIndex + 1} / ${readerStory.chapters.size}", 16)
            row {
                button("Prev") { navigateChapter(readerStory, chapter, -1) }
                button("TTS") { TtsForegroundService.start(this@MainActivity, readerStory.id, chapter.id) }
                button("TTS Prev") { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_PREVIOUS) }
                button("TTS Next") { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_NEXT) }
                button("Pause") { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_PAUSE) }
                button("Stop") { TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_STOP) }
                button("Next") { navigateChapter(readerStory, chapter, 1) }
            }
            val content = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
            val formattedText = TextCleanup.htmlToFormattedText(content)
            row {
                button("Copy") {
                    copyToClipboard("Chapter text", formattedText)
                    toast("Chapter copied")
                }
                button("Mark Read") {
                    val latest = storage.getStory(readerStory.id) ?: readerStory
                    storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = false))
                    toast("Marked as read")
                }
                button("Details") { showDetails(readerStory.id) }
            }
            val reader = WebView(this@MainActivity).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                loadDataWithBaseURL(null, ReaderContentRenderer.document(chapter.title, content), "text/html", "utf-8", null)
            }
            addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun showQueue() {
        screen("Download Manager") {
            val queue = storage.getQueue()
            val stats = queue.groupingBy { it.status }.eachCount()
            text(
                "Total ${queue.size} • Queued ${stats["pending"] ?: 0} • Active ${stats["downloading"] ?: 0} • " +
                    "Paused ${stats["paused"] ?: 0} • Done ${stats["completed"] ?: 0} • Failed ${stats["failed"] ?: 0} • Cancelled ${stats["cancelled"] ?: 0}"
            )
            row {
                button("Resume") { downloadEngine.resumeAll(); DownloadForegroundService.start(this@MainActivity); showQueue() }
                button("Pause") { downloadEngine.pauseAll(); showQueue() }
                button("Cancel All") { confirm("Cancel all active and pending downloads?") { downloadEngine.cancelAll(); showQueue() } }
                button("Retry Failed") { downloadEngine.retryFailed(); DownloadForegroundService.start(this@MainActivity); showQueue() }
                button("Clear Done") { downloadEngine.clearFinished(); showQueue() }
            }
            if (queue.isEmpty()) {
                text("No active downloads. Downloaded chapters will appear here.")
            }
            queue.groupBy { it.storyId }
                .values
                .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
                .forEach { jobs ->
                    val storyTitle = jobs.firstOrNull()?.storyTitle ?: "Unknown Story"
                    val summary = jobs.groupingBy { it.status }.eachCount()
                    addView(card {
                        text(storyTitle, 18)
                        text(
                            "${summary["completed"] ?: 0}/${jobs.size} chapters • " +
                                "${summary["pending"] ?: 0} queued • ${summary["downloading"] ?: 0} active • " +
                                "${summary["paused"] ?: 0} paused • ${summary["failed"] ?: 0} failed • ${summary["cancelled"] ?: 0} cancelled"
                        )
                        row {
                            if (jobs.any { it.status == "pending" || it.status == "downloading" }) {
                                button("Pause Story") {
                                    jobs.filter { it.status == "pending" || it.status == "downloading" }.forEach { downloadEngine.pauseJob(it.id) }
                                    showQueue()
                                }
                            }
                            if (jobs.any { it.status == "paused" }) {
                                button("Resume Story") {
                                    jobs.filter { it.status == "paused" }.forEach { downloadEngine.resumeJob(it.id) }
                                    DownloadForegroundService.start(this@MainActivity)
                                    showQueue()
                                }
                            }
                            if (jobs.any { it.status == "failed" || it.status == "cancelled" }) {
                                button("Retry Story") {
                                    downloadEngine.retryFailedForStory(jobs.first().storyId)
                                    DownloadForegroundService.start(this@MainActivity)
                                    showQueue()
                                }
                            }
                        }
                    })
                    jobs.sortedBy { it.chapterIndex }.forEach { job ->
                        addQueueJobCard(job)
                    }
                }
        }
    }

    private fun LinearLayout.addQueueJobCard(job: DownloadJob) {
        addView(card {
            text("  ${job.chapterIndex + 1}. ${job.chapter.title}")
            val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
            val nextRetry = job.nextRetryAt?.let { " • next retry ${formatRelativeTime(it)}" }.orEmpty()
            text("${job.status}${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry")
            job.errorCategory?.let { text("Category: $it${job.errorCode?.let { code -> " ($code)" } ?: ""}") }
            row {
                if (job.status == "pending" || job.status == "downloading") {
                    button("Pause") { downloadEngine.pauseJob(job.id); showQueue() }
                }
                if (job.status == "paused") {
                    button("Resume") { downloadEngine.resumeJob(job.id); DownloadForegroundService.start(this@MainActivity); showQueue() }
                }
                if (job.status == "pending" || job.status == "downloading" || job.status == "paused") {
                    button("Cancel") { downloadEngine.cancelJob(job.id); showQueue() }
                }
                if (job.status == "failed" || job.status == "cancelled") {
                    button("Retry") { downloadEngine.retryJob(job.id); DownloadForegroundService.start(this@MainActivity); showQueue() }
                }
                if (job.status in setOf("completed", "failed", "cancelled")) {
                    button("Remove") { downloadEngine.removeJob(job.id); showQueue() }
                }
            }
        })
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
        screen("Settings") {
            text("Appearance")
            text("Theme")
            row {
                button("Obsidian") { saveThemePreference("obsidian") }
                button("Midnight") { saveThemePreference("midnight") }
                button("Forest") { saveThemePreference("forest") }
                button("Classic Light") { saveThemePreference("classic-light") }
            }
            text("Active theme: ${displayPreferences.activeThemeId}")
            text("Fold Layout")
            row {
                button("Auto") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "auto")); showSettings() }
                button("Cover") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "cover")); showSettings() }
                button("Inner") { storage.saveDisplayPreferences(displayPreferences.copy(foldLayoutMode = "inner")); showSettings() }
            }
            text("Fold mode: ${displayPreferences.foldLayoutMode}")
            text("Downloads")
            val concurrency = numberInput(settings.downloadConcurrency.toString(), "Concurrency")
            val delay = numberInput(settings.downloadDelay.toString(), "Delay ms")
            val maxChapters = numberInput(settings.maxChaptersPerEpub.toString(), "Max chapters per EPUB")
            addView(concurrency); addView(delay); addView(maxChapters)
            text("Source Overrides")
            val sourceInputs = SourceRegistry.all().associate { provider ->
                val override = sourceSettings[provider.name]
                val sourceConcurrency = numberInput((override?.concurrency ?: settings.downloadConcurrency).toString(), "${provider.name} concurrency")
                val sourceDelay = numberInput((override?.delay ?: settings.downloadDelay).toString(), "${provider.name} delay ms")
                val sourceEnabled = CheckBox(this@MainActivity).apply {
                    text = "Override ${provider.name}"
                    isChecked = override != null
                }
                addView(sourceEnabled)
                addView(sourceConcurrency)
                addView(sourceDelay)
                row {
                    button("Reset ${provider.name}") {
                        val updated = storage.getSourceDownloadSettings().toMutableMap()
                        updated.remove(provider.name)
                        storage.saveSourceDownloadSettings(updated)
                        showSettings()
                    }
                }
                provider.name to Triple(sourceEnabled, sourceConcurrency, sourceDelay)
            }
            text("Text To Speech")
            storage.getTtsSession()?.let { session ->
                text("Saved TTS session: ${session.chapterTitle} (${session.currentChunkIndex + 1})")
                row {
                    button("Resume TTS") {
                        TtsForegroundService.command(this@MainActivity, TtsForegroundService.ACTION_RESUME_SESSION)
                    }
                    button("Clear TTS Session") {
                        storage.clearTtsSession()
                        showSettings()
                    }
                }
            }
            val pitch = decimalInput(ttsSettings.pitch.toString(), "Pitch")
            val rate = decimalInput(ttsSettings.rate.toString(), "Rate")
            val chunkSize = numberInput(ttsSettings.chunkSize.toString(), "Chunk size")
            addView(pitch); addView(rate); addView(chunkSize)
            text("Voice: ${ttsSettings.voiceIdentifier ?: "System default"}")
            row {
                button("Save") {
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
                button("Voice") { showTtsVoicePicker() }
                button("Manage Tabs") { showTabs() }
                button("Cleanup Rules") { showCleanupRules() }
            }
            text("Backup")
            row {
                button("Export JSON") { exportAndShare { storage.exportBackup() } }
                button("Import JSON") { importBackupLauncher.launch(arrayOf("application/json", "text/*")) }
            }
            row {
                button("Full Backup") { exportAndShare { storage.exportFullBackup() } }
                button("Restore Full") { importFullBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
            }
            button("Clear Local Storage") { confirm("Delete all novels, settings, and downloads?") { storage.clearAll(); showLibrary() } }
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
        val nightMode = when (themeId) {
            "classic-light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun showTabs() {
        screen("Manage Tabs") {
            val tabs = TabPlanning.normalizeOrders(storage.getTabs())
            row {
                val name = EditText(this@MainActivity).apply { hint = "New tab name" }
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                button("Add") {
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
                    text("${tab.name} (${storage.getLibrary().count { it.tabId == tab.id }} novels)")
                    row {
                        button("Up") {
                            if (index > 0) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index - 1))
                                showTabs()
                            }
                        }
                        button("Down") {
                            if (index < tabs.lastIndex) {
                                storage.saveTabs(TabPlanning.move(tabs, index, index + 1))
                                showTabs()
                            }
                        }
                        button("Rename") {
                            prompt("Rename Tab", tab.name) {
                                storage.saveTabs(TabPlanning.rename(tabs, tab.id, it))
                                showTabs()
                            }
                        }
                        button("Delete") {
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
        screen("Text Cleanup") {
            row {
                button("Export JSON") { share(storage.exportCleanupRules()) }
                button("Back") { showSettings() }
            }
            text("Sentences")
            val sentence = EditText(this@MainActivity).apply { hint = "Sentence to remove" }
            addView(sentence)
            button("Add Sentence") {
                val list = storage.getSentenceRemovalList()
                val result = SentenceRemovalPlanning.save(list, sentence.text.toString())
                if (!result.valid) {
                    toast(result.error ?: "Invalid sentence")
                } else {
                    storage.saveSentenceRemovalList(result.sentences)
                    showCleanupRules()
                }
            }
            storage.getSentenceRemovalList().forEachIndexed { index, item ->
                addView(card {
                    text(item)
                    row {
                        button("Edit") {
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
                        button("Delete") {
                            confirm("Are you sure you want to remove this sentence from the blocklist?") {
                                storage.saveSentenceRemovalList(SentenceRemovalPlanning.delete(storage.getSentenceRemovalList(), index))
                                showCleanupRules()
                            }
                        }
                    }
                })
            }
            text("Regex Rules")
            button("Add Regex Rule") { showRegexRuleDialog(null) }
            storage.getRegexRules().forEach { rule ->
                addView(card {
                    text("${rule.name}: /${rule.pattern}/${rule.flags} • ${rule.appliesTo} • ${if (rule.enabled) "enabled" else "disabled"}")
                    row {
                        button("Edit") { showRegexRuleDialog(rule) }
                        button("Toggle") { rule.enabled = !rule.enabled; storage.saveRegexRules(storage.getRegexRules()); showCleanupRules() }
                        button("Delete") { storage.saveRegexRules(storage.getRegexRules().filterNot { it.id == rule.id }); showCleanupRules() }
                    }
                })
            }
        }
    }

    private fun showRegexRuleDialog(existing: RegexCleanupRule?) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val name = EditText(this).apply { hint = "Rule name"; setText(existing?.name.orEmpty()) }
        val pattern = EditText(this).apply { hint = "Regex pattern"; setText(existing?.pattern.orEmpty()) }
        val flags = EditText(this).apply { hint = "Flags, e.g. im"; setText(existing?.flags ?: "i") }
        val appliesTo = EditText(this).apply { hint = "download, tts, or both"; setText(existing?.appliesTo ?: "both") }
        view.addView(name)
        view.addView(pattern)
        view.addView(flags)
        view.addView(appliesTo)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Quick Separator"
                setOnClickListener {
                    showQuickRegexBuilder { generated ->
                        name.setText(generated.name)
                        pattern.setText(generated.pattern)
                        flags.setText(generated.flags)
                    }
                }
            })
        }
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
            setPadding(24, 8, 24, 8)
        }
        val characters = EditText(this).apply { hint = "Character(s), e.g. =, -, ##" }
        val minCount = numberInput("5", "Minimum repetitions")
        val wholeLine = CheckBox(this).apply {
            text = "Whole line only"
            isChecked = true
        }
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
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
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
        val start = numberInput("1", "Start chapter")
        val end = numberInput(story.chapters.size.toString(), "End chapter")
        val countStart = numberInput("1", "Count start chapter")
        val count = numberInput(DownloadRangeSelection.DEFAULT_COUNT.toString(), "Chapters to download")
        val info = TextView(this).apply {
            text = "Total chapters: ${story.chapters.size}" + (bookmarkChapterNumber?.let { " • Bookmark at chapter $it" } ?: "")
            setPadding(0, 0, 0, 8)
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
            setPadding(24, 8, 24, 8)
        }
        val maxChapters = numberInput(current.maxChaptersPerEpub.toString(), "Max chapters per EPUB")
        val rangeStart = numberInput(current.rangeStart.toString(), "From chapter")
        val rangeEnd = numberInput(current.rangeEnd.coerceAtLeast(1).coerceAtMost(story.chapters.size).toString(), "To chapter")
        val startAfterBookmark = CheckBox(this).apply {
            text = "Start after bookmark"
            isChecked = current.startAfterBookmark && story.lastReadChapterId != null
            isEnabled = story.lastReadChapterId != null
        }
        view.addView(maxChapters)
        view.addView(rangeStart)
        view.addView(rangeEnd)
        view.addView(startAfterBookmark)
        view.addView(TextView(this).apply {
            text = "Downloaded chapters: ${story.chapters.count { it.downloaded }}. EPUB generation includes only downloaded chapters in range."
            setPadding(0, 8, 0, 0)
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
        screen("Working") { text("Starting...") }
        scope.launch {
            try {
                val existingBeforeSync = withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(url)?.let { provider ->
                        runCatching { storage.getStory(provider.getStoryId(url)) }.getOrNull()
                    }
                }
                val story = withContext(Dispatchers.IO) { syncEngine.fetchOrSync(url, tabId) { msg -> runOnUiThread { screen("Working") { text(msg) } } } }
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
                screen("Generating EPUB") { text("Preparing...") }
                val results = epubEngine.generate(story, chapters, maxChaptersPerFile, originalChapterNumbers) { msg -> runOnUiThread { screen("Generating EPUB") { text(msg) } } }
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

    private fun screen(title: String, block: LinearLayout.() -> Unit) {
        root.removeAllViews()
        root.addView(TextView(this).apply { text = title; textSize = 24f; setPadding(0, 0, 0, 12) })
        root.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun LinearLayout.row(block: LinearLayout.() -> Unit) = addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() })
    private fun LinearLayout.text(value: String, size: Int = 14) = addView(TextView(context).apply { text = value; textSize = size.toFloat(); setPadding(4, 4, 4, 4) })
    private fun LinearLayout.button(label: String, action: () -> Unit) = addView(Button(context).apply { text = label; setOnClickListener { action() } })
    private fun LinearLayout.coverImage(story: Story, widthDp: Int, heightDp: Int, tapToOpen: Boolean) {
        val url = story.coverUrl?.takeIf { it.isNotBlank() } ?: return
        val image = ImageView(context).apply {
            contentDescription = "${story.title} cover"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x11000000)
            setPadding(2, 2, 2, 2)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { setMargins(4, 4, 12, 4) }
            if (tapToOpen) setOnClickListener { showCoverDialog(story) }
        }
        addView(image)
        loadImage(url, image)
    }
    private fun numberInput(value: String, hintText: String) = EditText(this).apply { setText(value); hint = hintText; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    private fun decimalInput(value: String, hintText: String) = EditText(this).apply { setText(value); hint = hintText; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
    private fun card(block: LinearLayout.() -> Unit): View = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12); background = android.graphics.drawable.ColorDrawable(0x11000000); block() }
    private fun scroll(child: View): ScrollView = ScrollView(this).apply { addView(child) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun confirm(message: String, onYes: () -> Unit) = AlertDialog.Builder(this).setMessage(message).setPositiveButton("Confirm") { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    private fun prompt(title: String, value: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply { setText(value) }
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
        val view = TextView(this).apply {
            text = description
            textSize = 14f
            setPadding(24, 12, 24, 12)
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
            setPadding(8, 8, 8, 8)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520))
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
