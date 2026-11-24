package com.vinicius741.webnovelarchiver.feature.library

import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.narration.makeListRow
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.local.LocalSourceProvider
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeListGroup
import com.vinicius741.webnovelarchiver.ui.makeSmallSpinner
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTintedCard
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

/**
 * Local folder: the novels in the folder the user chose on this device, each added to the library
 * with one tap (or all at once). An added novel's chapters are read from its files right away.
 * Built from [LocalLibraryScreenModel]'s state; any state change rebuilds the screen.
 */
internal fun showLocalLibrary(host: ScreenHost) {
    val model =
        host.screenModels.get(AppRoute.LocalLibrary.stableKey) {
            LocalLibraryScreenModel(
                scan = {
                    val reader = LocalSourceProvider.reader
                    val name = reader.folderName()
                    LocalScan(name, if (name == null) emptyList() else reader.entries())
                },
                libraryIds = {
                    host.repository.downloadState.value.library
                        .mapTo(HashSet()) { it.id }
                },
                chooseFolder = { uri -> LocalSourceProvider.reader.setFolder(uri) },
            )
        }
    if (model.state.value.let { !it.scanned && !it.scanning }) model.rescan()
    LocalLibraryRenderer(host, model, model.refresh()).render()
}

private class LocalLibraryRenderer(
    private val host: ScreenHost,
    private val model: LocalLibraryScreenModel,
    private val state: LocalLibraryUiState,
) {
    private val app = host.app
    private val colors = ThemeManager.colors

    /**
     * The system folder picker, registered on every render under one key: a pick that finished
     * while the app was gone is delivered here as soon as the screen shows again.
     */
    private val picker: ActivityResultLauncher<Uri?> =
        app.activityResultRegistry.register(PICKER_KEY, ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree?.let { model.folderPicked(it.toString()) }
        }

    /** The import status line, patched in place while only the status text changes. */
    private var statusLine: TextView? = null

    fun render() =
        host.screen(
            route = AppRoute.LocalLibrary,
            title = "Local folder",
            subtitle = state.folderName ?: "Read your own books",
            actions =
                if (state.folderChosen) {
                    listOf(AppBarAction(R.drawable.wna_refresh, "Scan again") { model.rescan() })
                } else {
                    emptyList()
                },
            scrollable = true,
        ) {
            host.rerender = { showLocalLibrary(host) }
            when {
                !state.scanned -> addView(progress("Reading your folder..."))
                !state.folderChosen -> {
                    addView(intro())
                    fullButton("Choose folder", Btn.FILLED, R.drawable.wna_folder) { pickFolder() }
                }
                else -> novels(this)
            }
            state.error?.let {
                addView(makeText(app, it, Type.BODY_MEDIUM, colors.error).apply { setPadding(0, app.dp(Space.MD), 0, 0) })
            }
            section("How to organize it")
            addView(makeText(app, ORGANIZE_HELP, Type.BODY_SMALL, colors.onSurfaceVariant))
            var applied = state
            bindWhileAttached(host.scope, model.state) { next ->
                val line = statusLine
                if (next == applied || host.navigator.current != AppRoute.LocalLibrary) return@bindWhileAttached
                if (line != null && next.importStatus != null && next.copy(importStatus = applied.importStatus) == applied) {
                    // A new status message from the running import: no need to rebuild every row.
                    line.text = statusText(next)
                    applied = next
                } else {
                    showLocalLibrary(host)
                }
            }
        }

    private fun pickFolder() {
        runCatching { picker.launch(null) }.onFailure { host.toast("No app on this device can pick a folder") }
    }

    private fun statusText(state: LocalLibraryUiState): String {
        val waiting =
            state.queued.size
                .takeIf { it > 0 }
                ?.let { " · $it more waiting" }
                .orEmpty()
        return state.importStatus.orEmpty() + waiting
    }

    private fun novels(container: LinearLayout) {
        container.addView(
            makeListGroup(app).apply {
                addView(
                    makeListRow(
                        app,
                        state.folderName.orEmpty(),
                        "Tap to choose another folder",
                        leadIcon = R.drawable.wna_folder,
                        chevron = true,
                    ) {
                        if (!state.importing) pickFolder()
                    },
                )
            },
        )
        if (state.scanning) container.addView(progress("Reading your folder..."))
        if (state.importStatus != null) {
            val line = progress(statusText(state))
            statusLine = line.getChildAt(1) as TextView
            container.addView(line)
        }
        state.failures.forEach { failure ->
            container.addView(
                makeText(app, "Could not add $failure", Type.BODY_SMALL, colors.error).apply { setPadding(0, app.dp(Space.SM), 0, 0) },
            )
        }
        if (state.rows.isEmpty()) {
            if (!state.scanning) {
                container.addView(
                    makeEmptyState(
                        app,
                        message = "Add a subfolder with EPUB, PDF, TXT, Markdown or HTML files, or a book file, then scan again.",
                        title = "No books here yet",
                        iconRes = R.drawable.wna_menu_book,
                        actionLabel = "Scan again",
                        onAction = { model.rescan() },
                    ),
                )
            }
            return
        }
        container.section("Novels")
        val missing = state.notInLibrary
        if (missing.size > 1 && !state.importing) {
            container.fullButton("Add all ${missing.size}", Btn.TONAL, R.drawable.wna_add, topMarginDp = 0) {
                model.startImportAll()?.let(::import)
            }
        }
        container.addView(makeListGroup(app).apply { state.rows.forEach { addView(row(it)) } })
    }

    private fun row(row: LocalEntryRow): View {
        val entry = row.entry
        val supporting =
            if (row.inLibrary) {
                "In your library"
            } else {
                val files = if (entry.books == 1) "1 file" else "${entry.books} files"
                listOf(files, entry.formats.joinToString(", ") { it.label }).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        val trailing: View? =
            when {
                state.importingUrl == entry.storyUrl -> makeSmallSpinner(app, PROGRESS_SPINNER_DP)
                row.inLibrary -> null
                else ->
                    makeButton(app, "Add", Btn.TONAL, R.drawable.wna_add) {
                        if (model.startImport(entry.storyUrl)) import(entry.storyUrl)
                    }.apply {
                        isEnabled = !state.importing
                        alpha = if (state.importing) DISABLED_ALPHA else 1f
                        contentDescription = "Add ${entry.name}"
                    }
            }
        return makeListRow(
            app,
            entry.name,
            supporting,
            leadIcon = if (entry.isFolder) R.drawable.wna_folder else R.drawable.wna_book_open,
            leadTint = if (row.inLibrary) colors.primary else colors.onSurfaceVariant,
            trailing = trailing,
            chevron = row.inLibrary,
            onClick = if (row.inLibrary) ({ host.showDetails(entry.storyId) }) else null,
        )
    }

    /** Imports one local novel, then the next one an "Add all" run queued. The screen stays open. */
    private fun import(storyUrl: String) {
        host.syncStory(
            storyUrl,
            tabId = importTab(),
            onStatus = model::importStatus,
            onDone = { story ->
                host.toast("Added ${story.title}")
                model.importFinished()?.let(::import)
            },
            onError = { error ->
                // The reason is listed on the screen; an "Add all" run goes on with the next novel.
                model.importFailed(error.message ?: "unknown error")?.let(::import)
            },
        )
    }

    /** New local novels go where Add story would put them: the Library's current tab when it is a real tab. */
    private fun importTab(): String? {
        val tabs = host.repository.tabs.get()
        val current = host.repository.getDisplayPreferences().libraryTabId
        return tabs.firstOrNull { it.id == current }?.id ?: tabs.minByOrNull { it.order }?.id
    }

    private fun intro(): View =
        makeTintedCard(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val box = app.dp(HERO_ICON_BOX_DP)
            addView(
                ImageView(app).apply {
                    setImageDrawable(app.tintedIcon(R.drawable.wna_folder, colors.onPrimaryContainer))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = roundedBg(colors.primaryContainer, app.dp(Space.LG).toFloat())
                },
                LinearLayout.LayoutParams(box, box).apply { marginEnd = app.dp(Space.MD + 2) },
            )
            addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(app, "Your own books", Type.TITLE_MEDIUM, colors.onSurface))
                    addView(
                        makeText(
                            app,
                            "Choose a folder on this device. Each novel in it gets the reader, text-to-speech and audiobooks.",
                            Type.BODY_SMALL,
                            colors.onSurfaceVariant,
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.dp(Space.SM)
                }
        }

    private fun progress(message: String): LinearLayout =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(app.dp(Space.XS), app.dp(Space.MD), app.dp(Space.XS), app.dp(Space.SM))
            addView(makeSmallSpinner(app, PROGRESS_SPINNER_DP))
            addView(
                makeText(app, message, Type.BODY_MEDIUM, colors.onSurface).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(app.dp(Space.SM + 2), 0, 0, 0)
                },
            )
        }
}

private const val PICKER_KEY = "local_folder_picker"
private const val HERO_ICON_BOX_DP = 52
private const val PROGRESS_SPINNER_DP = 20
private const val DISABLED_ALPHA = 0.5f

private val ORGANIZE_HELP =
    """
    Make one subfolder per novel and put its files inside: EPUB, PDF, TXT, Markdown or HTML. Files are read in name order, so "Chapter 2" comes before "Chapter 10". A book file placed directly in the folder is a novel by itself.

    Chapters come from each book: an EPUB's table of contents, the "Chapter 1" headings of a text or PDF, or one chapter per file. PDFs need Android 15 or newer and real text, not scanned pages.

    Optional: a cover.jpg or cover.png, and a details.json with "title", "author", "description", "genre" and "status". Scan again after adding files; syncing a novel picks up its new chapters.
    """.trimIndent()
