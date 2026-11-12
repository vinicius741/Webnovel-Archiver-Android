package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

/** Screen-scoped story page references the live download loop patches; collaborators never rediscover views. */
internal data class DetailsBindings(
    val root: View,
    /** The Chapters tab's list, when that tab is open. */
    val chapters: RecyclerView?,
    /** The hero, when this render shows it (Overview, or any tab beside the inner-screen pane). */
    val hero: StoryHero?,
)

/** Applies one coherent download snapshot to every live story page surface. */
@Suppress("LongParameterList") // One snapshot's facts, always passed together.
internal fun DetailsBindings.patchDownloadStatus(
    host: ScreenHost,
    story: Story,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    chapterStatuses: Map<String, DownloadJobStatus>,
    waitingChapterIds: Set<String>,
    pacingStatus: DownloadPacingUiStatus?,
    isBusy: Boolean,
) {
    hero?.let { hero ->
        hero.coverage?.render(story)
        host.renderDetailsCta(hero.ctaSlot, story, summary, isBusy)
        host.renderHeroDownloadBanner(hero.bannerSlot, story, summary, pacingStatus)
    }
    chapterAdapter()?.refresh(story, chapterStatuses, waitingChapterIds)
}

/** The chapter rows' adapter inside the Chapters tab's list (behind its header), if that tab is open. */
internal fun DetailsBindings.chapterAdapter(): ChapterListAdapter? =
    when (val current = chapters?.adapter) {
        is ChapterListAdapter -> current
        is ConcatAdapter -> current.adapters.filterIsInstance<ChapterListAdapter>().singleOrNull()
        else -> null
    }
