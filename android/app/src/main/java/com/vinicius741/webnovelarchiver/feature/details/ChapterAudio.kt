package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning.needsAudio
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.narrationAction
import com.vinicius741.webnovelarchiver.feature.narration.playChapter
import com.vinicius741.webnovelarchiver.feature.narration.queueNarration
import com.vinicius741.webnovelarchiver.feature.narration.showNarration
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Chapter audio on the story page's Chapters tab (it replaced a separate Audio tab): each row's
 * audio state, a chapter's long-press actions, and the story's audio menu.
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

/** The story's audio menu: generate what is missing, move it to the front, regenerate or delete it. */
internal fun ScreenHost.showStoryAudioMenu(story: Story) {
    scope.launch {
        val statuses = runCatching { narration.statuses(story) }.getOrNull() ?: return@launch
        val playable = story.chapters.filter(TtsContinueListening::isPlayable)
        val voice = narration.currentVoice()
        val missing = playable.filter { statuses[it.id].needsAudio() }
        val packs = statuses.values.mapNotNull { it.pack() }
        val otherVoice = packs.filter { it.recipe.voice != voice }
        val queued =
            narration.queueState.value.jobs
                .filter { it.storyId == story.id }
        val items = mutableListOf<OptionsDialogItem>()
        if (missing.isNotEmpty()) {
            items +=
                OptionsDialogItem.Option("Generate all without audio (${missing.size})") {
                    val estimate = ChapterEstimate.of(packs)
                    confirm(
                        "Generate ${NarrationScreenPlanning.chapters(missing.size)}? That is about " +
                            "${NarrationPlanning.formatDuration(estimate.audioMs * missing.size)} of audio and " +
                            "${NarrationPlanning.formatBytes(estimate.bytes * missing.size)}.",
                        confirmLabel = "Generate",
                    ) { queueNarration(story.id, missing.map { it.id }) }
                }
        }
        if (queued.isNotEmpty()) {
            items +=
                OptionsDialogItem.Option("Move to front") { narrationAction("Could not reorder the queue") { moveStoryToTop(story.id) } }
        }
        if (otherVoice.isNotEmpty()) {
            items +=
                OptionsDialogItem.Option("Regenerate ${otherVoice.size} in ${voice.label}") {
                    queueNarration(story.id, otherVoice.map { it.chapterId }, NarrationOrigin.REGENERATE)
                }
        }
        if (packs.isNotEmpty() || queued.isNotEmpty()) {
            items += OptionsDialogItem.Divider
            if (queued.isNotEmpty()) {
                items +=
                    OptionsDialogItem.Option("Remove from queue (${queued.size})", destructive = true) {
                        narrationAction("Could not remove") { remove(queued.map { it.id }.toSet()) }
                    }
            }
            if (packs.isNotEmpty()) {
                items +=
                    OptionsDialogItem.Option(
                        "Delete all audio (${packs.size} · ${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })})",
                        destructive = true,
                    ) {
                        confirm("Delete the audio of ${NarrationScreenPlanning.chapters(packs.size)}?", confirmLabel = "Delete") {
                            narrationAction("Could not delete the audio") { deleteAudio(story.id, packs.map { it.chapterId }) }
                        }
                    }
            }
        }
        if (items.isEmpty()) return@launch toast("Download chapters to generate their audio")
        showStyledOptionsDialog("Audio · ${voice.label}", items)
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

/** Typical per-chapter size, from this story's audio when it has some. */
private data class ChapterEstimate(
    val audioMs: Long,
    val bytes: Long,
) {
    companion object {
        private const val DEFAULT_CHAPTER_MS = 20L * 60L * 1000L

        fun of(packs: List<NarrationPack>): ChapterEstimate {
            if (packs.isEmpty()) return ChapterEstimate(DEFAULT_CHAPTER_MS, NarrationPlanning.estimatedBytes(DEFAULT_CHAPTER_MS))
            return ChapterEstimate(packs.sumOf { it.index.totalDurationMs } / packs.size, packs.sumOf { it.index.bytes } / packs.size)
        }
    }
}

/** The label [chapterAudioLabels] gives a chapter whose audio is generated. */
internal const val AUDIO_READY_LABEL = "Audio"
