package com.vinicius741.webnovelarchiver.feature.narration

import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.strokeBg

/** Generated packs by story: those of chapters in the library, and the detached rest. */
internal class GeneratedAudio(
    val attached: Map<String, List<NarrationPack>>,
    val detached: Map<String, List<NarrationPack>>,
)

/** Splits the packs once per hub build: the split reads every pack's story, and two sections use it. */
internal fun ScreenHost.generatedAudio(): GeneratedAudio {
    val detached = narration.detachedPacks()
    val detachedKeys = detached.values.flatten().mapTo(HashSet()) { it.storyId to it.chapterId }
    val attached =
        narration.store
            .allPacks()
            .filter { (it.storyId to it.chapterId) !in detachedKeys }
            .groupBy { it.storyId }
    return GeneratedAudio(attached, detached)
}

/**
 * The Listen hub's Generated section: every story with generated audio (chapters, length, size,
 * voices), how much storage the audio uses, and audio whose story has left the library.
 */
internal fun LinearLayout.generatedSection(
    host: ScreenHost,
    audio: GeneratedAudio,
) {
    val attached = audio.attached
    val detached = audio.detached
    val used = host.narration.store.totalBytes()
    val free = host.narration.freeBytes()
    listenSectionLabel("Generated audio", "${NarrationPlanning.formatBytes(used)} · ${NarrationPlanning.formatBytes(free)} free")
    if (attached.isEmpty()) {
        listenHint("No chapter audio yet. ${NarrationScreenPlanning.HOW_TO_GENERATE}")
    } else {
        val list = makeListGroup(context)
        attached.entries
            .sortedByDescending { entry -> entry.value.maxOf { it.index.generatedAt } }
            .forEach { (storyId, storyPacks) ->
                val story = host.repository.story(storyId)
                val cover = story?.let { host.coverImage(it, COVER_WIDTH_DP, COVER_HEIGHT_DP, tapToOpen = false) }
                cover?.layoutParams = LinearLayout.LayoutParams(context.dp(COVER_WIDTH_DP), context.dp(COVER_HEIGHT_DP))
                cover?.isClickable = false
                list.addView(
                    makeListRow(
                        context,
                        title = story?.title ?: storyId,
                        supporting = packSummary(storyPacks),
                        leadIcon = R.drawable.wna_headphones,
                        lead = cover,
                        chevron = true,
                    ) { host.showNarrationStory(storyId) },
                )
            }
        addView(list)
    }
    if (detached.isNotEmpty()) detachedRow(host, detached)
}

/**
 * Audio for chapters no longer in the library (after restoring an older backup): one row that
 * deletes it all. Deleting a novel already deletes its audio, so this is only restore leftovers.
 */
private fun LinearLayout.detachedRow(
    host: ScreenHost,
    detached: Map<String, List<NarrationPack>>,
) {
    val packs = detached.values.flatten()
    val bytes = NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })
    val list = makeListGroup(context)
    list.addView(
        makeListRow(
            context,
            "Audio for chapters no longer in the library",
            "${packs.size} ${if (packs.size == 1) "chapter" else "chapters"} · $bytes · tap to delete",
            leadIcon = R.drawable.wna_delete,
            leadTint = ThemeManager.colors.error,
        ) {
            host.confirm("Delete this audio ($bytes)?", confirmLabel = "Delete") {
                host.narrationAction("Could not delete the audio") {
                    detached.forEach { (storyId, storyPacks) -> deleteAudio(storyId, storyPacks.map { it.chapterId }) }
                }
            }
        },
    )
    addView(
        list,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(Spacing.MD)
        },
    )
}

internal fun packSummary(packs: List<NarrationPack>): String {
    val voices = packs.map { it.recipe.voice.label }.distinct().joinToString(", ")
    val duration = NarrationPlanning.formatDuration(packs.sumOf { it.index.totalDurationMs })
    return "${packs.size} ${if (packs.size == 1) "chapter" else "chapters"} · $duration · " +
        "${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })} · $voices"
}

private const val COVER_WIDTH_DP = 40
private const val COVER_HEIGHT_DP = 60
