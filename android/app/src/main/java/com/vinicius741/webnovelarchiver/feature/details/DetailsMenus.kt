package com.vinicius741.webnovelarchiver.feature.details

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.epub.EpubConfigPlanning
import com.vinicius741.webnovelarchiver.feature.library.LibrarySelectionPlanning
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.feature.story.applyCleanup
import com.vinicius741.webnovelarchiver.feature.story.generateConfiguredEpub
import com.vinicius741.webnovelarchiver.feature.story.openEpubForStory
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
 * The story page's sheets: the ⋮ menu (every action the old Details overflow had, grouped, with
 * icons) and the EPUB sheet behind the EPUB quick action.
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

/** The ⋮ menu: full sync, Patreon link, EPUB, cleanup, the other tabs, and Delete isolated last. */
internal fun ScreenHost.showDetailsOverflow(story: com.vinicius741.webnovelarchiver.domain.model.Story) {
    val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
    val top =
        buildList {
            if (StoryActionGuards.canSync(story) && provider?.supportsLatestChapterSync == true) {
                add(
                    SheetRow("Full sync", R.drawable.wna_refresh, "Re-check every chapter and the details", busyGuarded = true) {
                        syncStory(story, StorySyncMode.Full)
                    },
                )
            }
            if (StoryActionGuards.canModifyStory(story)) {
                val label = if (story.effectivePatreonUrl.isNullOrBlank()) "Add Patreon link" else "Edit Patreon link"
                add(SheetRow(label, R.drawable.wna_edit, busyGuarded = true) { showPatreonLinkDialog(story) })
            }
        }
    val epub =
        listOf(
            SheetRow("EPUB settings", R.drawable.wna_settings, busyGuarded = true) { showEpubConfigDialog(story) },
            SheetRow("EPUB files", R.drawable.wna_folder, epubFilesLabel(story), busyGuarded = true) { showLegacyEpubs(story.id) },
        )
    val text =
        listOf(
            SheetRow("Apply text cleanup", R.drawable.wna_cleaning, "Run your cleanup rules on saved chapters", busyGuarded = true) {
                applyCleanup(story)
            },
        )
    val explore =
        listOf(
            SheetRow("Trends", R.drawable.wna_chart) { switchStoryTab(story.id, StoryTab.TRENDS) },
            SheetRow("AI controls", R.drawable.wna_auto_awesome) { switchStoryTab(story.id, StoryTab.AI) },
            SheetRow("Narration audio", R.drawable.wna_graphic_eq) { switchStoryTab(story.id, StoryTab.AUDIO) },
        )
    val delete =
        listOf(SheetRow("Delete novel", R.drawable.wna_delete, destructive = true, busyGuarded = true) { confirmDeleteStory(story) })
    showStorySheet(
        story.title,
        "More options",
        story.id,
        listOf(
            null to top,
            "EPUB" to epub,
            "Text" to text,
            "Explore" to explore,
            null to delete,
        ),
    )
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

/** The EPUB quick action: generate or regenerate, read, settings and files. */
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
            SheetRow("EPUB settings", R.drawable.wna_settings, busyGuarded = true) { showEpubConfigDialog(story) },
            SheetRow("EPUB files", R.drawable.wna_folder, epubFilesLabel(story), busyGuarded = true) { showLegacyEpubs(story.id) },
        )
    showStorySheet("EPUB", subtitle, story.id, listOf(null to rows))
}

private fun epubFileCount(story: com.vinicius741.webnovelarchiver.domain.model.Story): Int =
    story.epubPaths?.size?.takeIf { it > 0 } ?: if (story.epubPath.isNullOrBlank()) 0 else 1

private fun epubFilesLabel(story: com.vinicius741.webnovelarchiver.domain.model.Story): String =
    when (val count = epubFileCount(story)) {
        0 -> "No files yet"
        1 -> "1 file"
        else -> "$count files"
    }
