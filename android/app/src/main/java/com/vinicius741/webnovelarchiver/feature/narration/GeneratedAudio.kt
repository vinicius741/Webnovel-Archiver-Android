package com.vinicius741.webnovelarchiver.feature.narration

import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.details.ChapterSheets
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * The Listen hub's Generated section: every story with generated audio (chapters, length, size,
 * voices), how much storage the audio uses, and audio whose story has left the library.
 */
internal fun LinearLayout.generatedSection(
    host: ScreenHost,
    model: ListenScreenModel,
    content: ListenContent,
    compression: CompressionStatusView,
) {
    val attached = content.audio.attached
    val detached = content.audio.detached
    listenSectionLabel(
        "Generated audio",
        "${NarrationPlanning.formatBytes(content.usedBytes)} · ${NarrationPlanning.formatBytes(content.freeBytes)} free",
    )
    addView(
        compression.view,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(Spacing.MD)
        },
    )
    if (content.suggestions.isNotEmpty()) suggestionsCard(host, content.suggestions)
    if (attached.isEmpty()) {
        listenHint("No chapter audio yet. ${NarrationScreenPlanning.HOW_TO_GENERATE}")
    } else {
        val list = makeListGroup(context)
        attached.entries
            .sortedByDescending { entry -> entry.value.maxOf { it.index.generatedAt } }
            .forEach { (storyId, storyPacks) ->
                val story = content.audioStories[storyId]
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
                    ) { ChapterSheets(host).showAudio(storyId, fromListen = true) },
                )
            }
        addView(list)
    }
    if (detached.isNotEmpty()) detachedRow(host, model, detached)
}

/**
 * Clean-ups from the bookmarks and last listens (finished, on a break, already heard), each with
 * what it frees and one button. The delete waits behind an Undo bar.
 */
private fun LinearLayout.suggestionsCard(
    host: ScreenHost,
    suggestions: List<ListenSuggestion>,
) {
    val colors = ThemeManager.colors
    addView(
        makeText(context, "SUGGESTIONS", Type.LABEL_SMALL, colors.primary).apply {
            letterSpacing = SUGGESTION_LETTER_SPACING
            setPadding(context.dp(Spacing.XS), 0, 0, context.dp(Spacing.SM))
        },
    )
    val list = makeListGroup(context)
    suggestions.forEach { suggestion ->
        val bytes = NarrationPlanning.formatBytes(suggestion.bytes)
        val title = suggestion.titles.joinToString(", ")
        val (icon, tint) =
            when (suggestion.kind) {
                ListenSuggestion.Kind.FINISHED -> R.drawable.wna_check_circle to colors.tertiary
                ListenSuggestion.Kind.ON_BREAK -> R.drawable.wna_pause to colors.secondary
                ListenSuggestion.Kind.HEARD -> R.drawable.wna_bookmark to colors.bookmark
            }
        val (heading, supporting, action) =
            when (suggestion.kind) {
                ListenSuggestion.Kind.FINISHED ->
                    Triple("$title is finished", "Bookmark on the last chapter · frees $bytes", "Delete all its audio")
                ListenSuggestion.Kind.ON_BREAK ->
                    Triple(
                        "$title: no listening for ${suggestion.idleDays} days",
                        "Keep the next ${ListenSuggestionPlanning.KEEP_ON_BREAK} from your bookmark, delete the rest · frees $bytes",
                        "Put on a break",
                    )
                ListenSuggestion.Kind.HEARD ->
                    Triple("Audio you already heard", "Before the bookmark in $title · frees $bytes", "Clear heard audio")
            }
        val button =
            makeButton(context, action, if (suggestion.kind == ListenSuggestion.Kind.FINISHED) Btn.ERROR else Btn.SURFACE) {
                ChapterSheets(host).deleteWithUndo(
                    suggestion.chapters,
                    "Deleting audio of ${NarrationScreenPlanning.chapters(suggestion.chapterCount)} · $bytes",
                )
            }.apply {
                minHeight = context.dp(SUGGESTION_BUTTON_DP)
                minimumHeight = context.dp(SUGGESTION_BUTTON_DP)
                setPadding(context.dp(Spacing.LG), 0, context.dp(Spacing.LG), 0)
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(context, heading, Type.TITLE_SMALL, colors.onSurface))
                addView(
                    makeText(context, supporting, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, context.dp(2), 0, 0) },
                )
                addView(
                    button,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(SUGGESTION_BUTTON_DP)).apply {
                        topMargin = context.dp(Spacing.SM + 2)
                    },
                )
            }
        list.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dp(Spacing.LG), context.dp(Spacing.MD + 2), context.dp(Spacing.LG), context.dp(Spacing.MD + 2))
                addView(
                    ImageView(context).apply { setImageDrawable(context.tintedIcon(icon, tint)) },
                    LinearLayout.LayoutParams(context.dp(SUGGESTION_ICON_DP), context.dp(SUGGESTION_ICON_DP)).apply {
                        marginEnd = context.dp(Spacing.MD + 2)
                        topMargin = context.dp(2)
                    },
                )
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
        )
    }
    addView(
        list,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(Spacing.LG)
        },
    )
}

/**
 * Audio for chapters no longer in the library (after restoring an older backup): one row that
 * deletes it all. Deleting a novel already deletes its audio, so this is only restore leftovers.
 */
private fun LinearLayout.detachedRow(
    host: ScreenHost,
    model: ListenScreenModel,
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
            host.confirm("Delete this audio ($bytes)?", confirmLabel = "Delete") { model.deleteDetachedAudio() }
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

private const val SUGGESTION_BUTTON_DP = 36
private const val SUGGESTION_ICON_DP = 24
private const val SUGGESTION_LETTER_SPACING = 0.08f
private const val COVER_WIDTH_DP = 40
private const val COVER_HEIGHT_DP = 60
