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

/**
 * The Listen hub's Generated section: every story with generated audio (chapters, length, size,
 * voices), how much storage the audio uses, and audio whose story has left the library.
 */
internal fun LinearLayout.generatedSection(host: ScreenHost) {
    val packs = host.narration.store.allPacks()
    val detached = host.narration.detachedPacks()
    val attached = packs.filter { pack -> detached[pack.storyId]?.contains(pack) != true }.groupBy { it.storyId }
    val used = host.narration.store.totalBytes()
    val free = host.narration.freeBytes()
    listenSectionLabel("Generated audio", "${NarrationPlanning.formatBytes(used)} · ${NarrationPlanning.formatBytes(free)} free")
    if (attached.isEmpty()) {
        listenHint("No chapter audio yet. Open a chapter and tap Read aloud to generate it.")
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
    storageCard(used, free)
    if (detached.isNotEmpty()) detachedSection(host, detached)
}

private fun LinearLayout.storageCard(
    used: Long,
    free: Long,
) {
    val colors = ThemeManager.colors
    val card =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(Spacing.LG)
            setPadding(pad, context.dp(Spacing.MD + 2), pad, context.dp(Spacing.MD + 2))
            background = strokeBg(colors.elevation1, context.dp(STORAGE_RADIUS_DP).toFloat(), colors.line, context.dp(1).coerceAtLeast(1))
            val top =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        makeText(context, "Audio uses ${NarrationPlanning.formatBytes(used)}", Type.TITLE_SMALL, colors.onSurface),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(makeText(context, "${NarrationPlanning.formatBytes(free)} free", Type.BODY_SMALL, colors.onSurfaceVariant))
                }
            addView(top)
            val line =
                PlayerProgressLine(context, rounded = true, trackColor = colors.elevation4).apply {
                    fraction = if (used + free > 0) used.toFloat() / (used + free) else 0f
                }
            addView(
                line,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(BAR_HEIGHT_DP)).apply {
                    topMargin =
                        context.dp(Spacing.SM + 2)
                },
            )
        }
    addView(
        card,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin =
                context.dp(Spacing.MD)
        },
    )
}

private fun LinearLayout.detachedSection(
    host: ScreenHost,
    detached: Map<String, List<NarrationPack>>,
) {
    listenSectionLabel("Detached audio")
    listenHint("Audio for chapters no longer in the library, for example after restoring an older backup. It stays until you delete it.")
    val list = makeListGroup(context)
    detached.forEach { (storyId, storyPacks) ->
        val title = host.repository.story(storyId)?.title ?: "Deleted story"
        list.addView(
            makeListRow(context, title, packSummary(storyPacks), leadIcon = R.drawable.wna_delete, leadTint = ThemeManager.colors.error) {
                host.confirm(
                    "Delete this audio (${NarrationPlanning.formatBytes(storyPacks.sumOf { it.index.bytes })})?",
                    confirmLabel = "Delete",
                ) {
                    host.narrationAction("Could not delete the audio") { deleteAudio(storyId, storyPacks.map { it.chapterId }) }
                }
            },
        )
    }
    addView(list)
}

internal fun packSummary(packs: List<NarrationPack>): String {
    val voices = packs.map { it.recipe.voice.label }.distinct().joinToString(", ")
    val duration = NarrationPlanning.formatDuration(packs.sumOf { it.index.totalDurationMs })
    return "${packs.size} ${if (packs.size == 1) "chapter" else "chapters"} · $duration · " +
        "${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })} · $voices"
}

private const val COVER_WIDTH_DP = 40
private const val COVER_HEIGHT_DP = 60
private const val STORAGE_RADIUS_DP = 14
private const val BAR_HEIGHT_DP = 6
