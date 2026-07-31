package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.updateChapterCoverageSummary

/** Screen-scoped Details references; collaborators never need to rediscover these views. */
internal data class DetailsBindings(
    val root: View,
    val chapters: RecyclerView,
    val headerProgressSummary: View?,
    val bannerSlot: ViewGroup?,
    val downloadActionSlot: LinearLayout?,
)

/** Applies one coherent download snapshot to every live Details surface. */
internal fun DetailsBindings.patchDownloadStatus(
    host: ScreenHost,
    story: Story,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    chapterStatuses: Map<String, DownloadJobStatus>,
    waitingChapterIds: Set<String>,
    pacingStatus: DownloadPacingUiStatus?,
    isBusy: Boolean,
) {
    headerProgressSummary?.let {
        updateChapterCoverageSummary(
            it,
            StoryBookmarkPlanning.downloadedFlags(story),
            StoryBookmarkPlanning.bookmarkFraction(story),
            story.downloadedChapters,
            story.totalChapters,
        )
    }
    downloadActionSlot?.let { host.renderDetailsDownloadAction(it, story, summary, isBusy) }

    val adapter =
        when (val current = chapters.adapter) {
            is ChapterListAdapter -> current
            is ConcatAdapter -> current.adapters.filterIsInstance<ChapterListAdapter>().singleOrNull()
            else -> null
        }
    adapter?.let {
        val query = it.currentQuery()
        val filter = it.currentFilter()
        val filtered = filterDetailsChapters(story, query, filter)
        val empty = filtered.isEmpty()
        it.update(
            if (empty) listOf(-1 to Chapter(title = "No chapters match this view.")) else filtered,
            story,
            empty,
            query,
            filter,
            chapterStatuses,
            waitingChapterIds,
        )
    }

    bannerSlot?.let { slot ->
        slot.removeAllViews()
        if (shouldShowDetailsBanner(summary)) {
            slot.addView(makeDownloadProgressBanner(host.app, summary, pacingStatus) { host.showQueue() })
        }
    }
}
