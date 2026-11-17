package com.vinicius741.webnovelarchiver.feature.details

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.epub.EpubConfigPlanning
import com.vinicius741.webnovelarchiver.feature.library.LibrarySelectionPlanning
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.story.applyCleanup
import com.vinicius741.webnovelarchiver.feature.story.generateConfiguredEpub
import com.vinicius741.webnovelarchiver.feature.story.openEpubForStory
import com.vinicius741.webnovelarchiver.feature.story.shareAll
import com.vinicius741.webnovelarchiver.feature.story.showEpubConfigDialog
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeSheetSection
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The story page's sheets: the ⋮ menu (re-check, cleanup, delete) and the EPUB sheet behind the
 * EPUB quick action.
 */

/** One row of a story sheet; [busyGuarded] rows wait while a sync, cleanup or EPUB run is in progress. */
private class SheetRow(
    val label: String,
    val iconRes: Int,
    val supporting: String? = null,
    val destructive: Boolean = false,
    val busyGuarded: Boolean = false,
    val action: () -> Unit,
)

private fun ScreenHost.showStorySheet(
    title: String,
    subtitle: String?,
    storyId: String,
    groups: List<Pair<String?, List<SheetRow>>>,
) {
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var handle: BottomSheetHandle? = null
    groups.filter { it.second.isNotEmpty() }.forEach { (label, rows) ->
        label?.let { body.addView(makeSheetSection(app, it)) }
        rows.forEach { row ->
            body.addView(
                makeSheetRow(app, row.label, row.supporting, row.iconRes, destructive = row.destructive) {
                    handle?.dismiss()
                    if (row.busyGuarded && storyOperation?.storyId == storyId) {
                        toast("Please wait for the current operation to finish")
                    } else {
                        row.action()
                    }
                },
            )
        }
    }
    handle = showBottomSheet(app, title = title, subtitle = subtitle, body = body)
}

/** The ⋮ menu: re-check all chapters, text cleanup, and Delete isolated last. */
internal fun ScreenHost.showDetailsOverflow(story: com.vinicius741.webnovelarchiver.domain.model.Story) {
    val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
    val top =
        buildList {
            if (StoryActionGuards.canSync(story) && provider?.supportsLatestChapterSync == true) {
                add(
                    SheetRow(
                        "Re-check all chapters",
                        R.drawable.wna_refresh,
                        "Sync looks only for new ones; this checks every chapter and the details",
                        busyGuarded = true,
                    ) {
                        syncStory(story, StorySyncMode.Full)
                    },
                )
            }
            add(
                SheetRow("Apply text cleanup", R.drawable.wna_cleaning, "Run your cleanup rules on saved chapters", busyGuarded = true) {
                    applyCleanup(story)
                },
            )
        }
    val delete =
        listOf(SheetRow("Delete novel", R.drawable.wna_delete, destructive = true, busyGuarded = true) { confirmDeleteStory(story) })
    showStorySheet(story.title, "More options", story.id, listOf(null to top, null to delete))
}

private fun ScreenHost.confirmDeleteStory(story: com.vinicius741.webnovelarchiver.domain.model.Story) {
    val warning = LibrarySelectionPlanning.removedChaptersWarning(story.chapters.count { it.isRemovedFromSource })
    confirm("Delete \"${story.title}\"? This action cannot be undone.$warning", confirmLabel = "Delete") {
        scope.launch {
            repository.deleteStory(story.id)
            narration.deleteStory(story.id)
            showLibrary()
        }
    }
}

/** The EPUB quick action: generate or regenerate, read, share, and settings. */
internal fun ScreenHost.showEpubSheet(storyId: String) {
    val story = repository.story(storyId) ?: return
    val files = epubFileCount(story)
    val generating = storyOperation?.let { it.storyId == storyId && it.kind == StoryOperationKind.EPUB } == true
    val perFile = EpubConfigPlanning.resolve(story, repository.settings.get().maxChaptersPerEpub).maxChaptersPerEpub
    val subtitle =
        when {
            generating -> "Generating…"
            files > 0 && story.epubStale == true -> "Out of date · ${epubFilesLabel(story)}"
            files > 0 -> "Ready · ${epubFilesLabel(story)}"
            else -> "${story.downloadedChapters} chapters offline"
        }
    val generateLabel = if (files > 0) "Regenerate EPUB" else "Generate EPUB"
    val rows =
        listOf(
            SheetRow(generateLabel, R.drawable.wna_book_open, "Up to $perFile chapters per file", busyGuarded = true) {
                if (story.downloadedChapters == 0) toast("Download a chapter first") else generateConfiguredEpub(story)
            },
            SheetRow(
                "Read EPUB",
                R.drawable.wna_menu_book,
                if (files >
                    0
                ) {
                    "Open in your EPUB reader"
                } else {
                    "Generate it first"
                },
                busyGuarded = true,
            ) {
                if (files == 0) toast("Generate an EPUB first") else openEpubForStory(story)
            },
            SheetRow(
                "Share EPUB",
                R.drawable.wna_share,
                if (files >
                    0
                ) {
                    epubFilesLabel(story)
                } else {
                    "Generate it first"
                },
                busyGuarded = true,
            ) {
                if (files == 0) toast("Generate an EPUB first") else shareEpubs(story)
            },
            SheetRow("EPUB settings", R.drawable.wna_settings, busyGuarded = true) { showEpubConfigDialog(story) },
        )
    showStorySheet("EPUB", subtitle, story.id, listOf(null to rows))
}

/** Shares the story's current EPUB file(s) in one chooser. */
private fun ScreenHost.shareEpubs(story: com.vinicius741.webnovelarchiver.domain.model.Story) {
    val paths = story.epubPaths?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOfNotNull(story.epubPath)
    val files = paths.mapNotNull { repository.resolveAbsolutePath(it) }
    if (files.isEmpty()) toast("EPUB file not found. Please regenerate.") else shareAll(files, "Share EPUB")
}

private fun epubFileCount(story: com.vinicius741.webnovelarchiver.domain.model.Story): Int =
    story.epubPaths?.size?.takeIf { it > 0 } ?: if (story.epubPath.isNullOrBlank()) 0 else 1

private fun epubFilesLabel(story: com.vinicius741.webnovelarchiver.domain.model.Story): String =
    when (val count = epubFileCount(story)) {
        0 -> "No files yet"
        1 -> "1 file"
        else -> "$count files"
    }
