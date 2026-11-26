package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.narrationAction
import com.vinicius741.webnovelarchiver.feature.narration.playChapter
import com.vinicius741.webnovelarchiver.feature.narration.queueNarration
import com.vinicius741.webnovelarchiver.feature.narration.showNarration
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import kotlinx.coroutines.launch

/*
 * Chapter audio on the story page's Chapters tab (it replaced a separate Audio tab): each row's
 * audio state, and a chapter's long-press actions.
 */

/** Opens the story's Chapters tab, scrolled to [chapterId] when given; where a chapter's audio lives. */
internal fun ScreenHost.showStoryChapters(
    storyId: String,
    chapterId: String? = null,
) {
    if (repository.story(storyId) == null) return showNarration()
    if (chapterId != null) StoryTabMemory.focusChapter[storyId] = chapterId
    switchStoryTab(storyId, StoryTab.CHAPTERS)
}

/**
 * Each chapter's audio state for its row ("Audio", "Audio queued"…), by chapter id, from the queue
 * and the published packs. Reads no chapter text, so it is cheap enough for every refresh.
 */
internal fun ScreenHost.chapterAudioLabels(story: Story): Map<String, String> {
    val labels = HashMap<String, String>()
    val running =
        narration.progress.value
            ?.takeUnless { it.preparing }
            ?.job
            ?.id
    narration.queueState.value.jobs
        .filter { it.storyId == story.id }
        .forEach { job ->
            val label =
                when {
                    job.id == running -> "Generating audio"
                    job.state == NarrationJobState.FAILED -> "Audio failed"
                    else -> "Audio queued"
                }
            labels.putIfAbsent(job.chapterId, label)
        }
    runCatching {
        narration.store.packsFor(
            story.id,
        )
    }.getOrDefault(emptyList()).forEach { labels.putIfAbsent(it.chapterId, AUDIO_READY_LABEL) }
    return labels
}

/**
 * Each chapter's audio for the bulk actions, by chapter id: queued (failed jobs count as queued,
 * they still hold a place), generating, or ready with its pack's size and length. In memory only.
 */
internal fun chapterAudioInfo(
    narration: NarrationCoordinator,
    story: Story,
): Map<String, ChapterAudioInfo> {
    val info = HashMap<String, ChapterAudioInfo>()
    val running =
        narration.progress.value
            ?.takeUnless { it.preparing }
            ?.job
            ?.id
    narration.queueState.value.jobs
        .filter { it.storyId == story.id }
        .forEach { job ->
            val state = if (job.id == running) ChapterAudioState.GENERATING else ChapterAudioState.QUEUED
            info.putIfAbsent(job.chapterId, ChapterAudioInfo(state))
        }
    runCatching { narration.store.packsFor(story.id) }.getOrDefault(emptyList()).forEach { pack ->
        info.putIfAbsent(pack.chapterId, ChapterAudioInfo(ChapterAudioState.READY, pack.index.bytes, pack.index.totalDurationMs))
    }
    return info
}

/** A chapter's long-press actions: read it, listen, and generate, regenerate or delete its audio. */
internal fun ScreenHost.showChapterActions(
    story: Story,
    chapter: Chapter,
) {
    scope.launch {
        val status = runCatching { narration.status(story.id, chapter.id) }.getOrDefault(NarrationChapterStatus.None)
        val job =
            narration.queueState.value.jobs
                .firstOrNull { it.storyId == story.id && it.chapterId == chapter.id }
        val items = mutableListOf<OptionsDialogItem>()
        if (chapter.downloaded) items += OptionsDialogItem.Option("Open in reader") { showReader(story.id, chapter.id) }
        if (TtsContinueListening.isPlayable(chapter)) {
            items += OptionsDialogItem.Option("Listen") { playChapter(story.id, chapter.id) }
            when {
                job != null && status !is NarrationChapterStatus.Generating ->
                    items +=
                        OptionsDialogItem.Option("Move to front") { narrationAction("Could not move the chapter") { moveToTop(job.id) } }
                job == null && status.pack() == null ->
                    items += OptionsDialogItem.Option("Generate audio") { queueNarration(story.id, listOf(chapter.id)) }
                else -> Unit
            }
            if (status is NarrationChapterStatus.Failed && job != null) {
                items += OptionsDialogItem.Option("Retry") { narrationAction("Could not retry") { retry(job.id) } }
            }
            if (status.pack() != null && job == null) {
                items +=
                    OptionsDialogItem.Option("Regenerate audio") {
                        queueNarration(story.id, listOf(chapter.id), NarrationOrigin.REGENERATE, front = true)
                    }
            }
        }
        if (job != null || status.pack() != null) {
            items += OptionsDialogItem.Divider
            items +=
                OptionsDialogItem.Option(if (status.pack() != null) "Delete audio" else "Remove from queue", destructive = true) {
                    narrationAction("Could not delete the audio") { deleteAudio(story.id, listOf(chapter.id)) }
                }
        }
        if (items.isEmpty()) return@launch
        showStyledOptionsDialog("${chapter.title} · ${statusLabel(status)}", items)
    }
}

internal fun NarrationChapterStatus.pack(): NarrationPack? =
    when (this) {
        is NarrationChapterStatus.Ready -> pack
        is NarrationChapterStatus.Outdated -> pack
        else -> null
    }

internal fun statusLabel(status: NarrationChapterStatus): String =
    when (status) {
        NarrationChapterStatus.None -> "No audio"
        is NarrationChapterStatus.Queued -> "Queued · #${status.position}"
        is NarrationChapterStatus.Generating -> "Generating · ${NarrationScreenPlanning.percent(status.progress)}"
        is NarrationChapterStatus.Failed -> "Failed · ${status.reason}"
        is NarrationChapterStatus.Ready ->
            "Audio · ${NarrationPlanning.formatDuration(status.pack.index.totalDurationMs)} · ${status.pack.recipe.voice.label}"
        is NarrationChapterStatus.Outdated -> "Audio of older text · plays as generated"
    }

/** The label [chapterAudioLabels] gives a chapter whose audio is generated. */
internal const val AUDIO_READY_LABEL = "Audio"
