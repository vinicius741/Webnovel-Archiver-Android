package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.narrationScope
import com.vinicius741.webnovelarchiver.feature.narration.queueNarration
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeStepper
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.showUndoBar
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The On this phone card's sheets: Download text (the next chapters after the bookmark,
 * everything left, Pick chapters…) and Audio (generate the next chapters from the listening place;
 * free up space with what was already heard, taking a break, or all of it; Pick chapters…). Every
 * option shows how many chapters (and how much audio) it touches. Audio deletes wait a few seconds
 * behind an Undo bar before anything is removed.
 */

/** Stepper values the sheets remember for this run of the app. */
private object ChapterSheetMemory {
    var downloadNext = 20
    var generateNext = 10
    var keepOnBreak = 10
}

/** The On this phone card's sheets and the audio deletes they start, for [host]'s screen. */
internal class ChapterSheets(
    private val host: ScreenHost,
) {
    /** Download text: the next chapters after the bookmark, everything left, or Pick chapters…. */
    fun showText(storyId: String) {
        with(host) {
            val story = repository.story(storyId) ?: return
            if (!StoryActionGuards.canQueueDownloads(story)) {
                toast(
                    if (StoryActionGuards.canModifyStory(story)) {
                        "This novel’s source is unavailable, so nothing can download"
                    } else {
                        StoryActionGuards.archivedActionMessage("Downloading")
                    },
                )
                return
            }
            val chapters = story.chapters
            val bookmark = bookmarkIndexOf(story)
            val downloading =
                storyPageModel(storyId)
                    .refresh()
                    .chapterStatuses
                    .filterValues {
                        it == DownloadJobStatus.Pending || it == DownloadJobStatus.Downloading || it == DownloadJobStatus.Paused
                    }.keys
            val left = chapters.indices.filter { !chapters[it].downloaded && chapters[it].id !in downloading }
            val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            lateinit var sheet: BottomSheetHandle

            fun download(indexes: List<Int>) {
                if (indexes.isEmpty()) return
                sheet.dismissThen { queueDownload(story, indexes) }
            }
            val from = bookmark.coerceAtLeast(0)
            val nextIndexes = { ChapterActionPlanning.nextToDownload(chapters, from, ChapterSheetMemory.downloadNext, downloading) }
            val next =
                SheetOption(
                    app,
                    R.drawable.wna_skip_next,
                    "The next chapters after your bookmark",
                    if (bookmark >= 0) "Ones not downloaded yet, from Ch. ${bookmark + 1}" else "Ones not downloaded yet, from the start",
                ) { download(nextIndexes()) }
            next.addBelow(
                makeStepper(
                    app,
                    ChapterSheetMemory.downloadNext.toDouble(),
                    StepperSpec(1.0, 1.0, MAX_STEPPER, label = "Chapters to download"),
                ) {
                    ChapterSheetMemory.downloadNext = it.toInt()
                    next.setCount(nextIndexes().size)
                    null
                }.view,
            )
            next.setCount(nextIndexes().size)
            body.addView(next.view)
            body.addView(
                SheetOption(app, R.drawable.wna_download, "Everything left", "The whole rest of the novel") { download(left) }
                    .apply { setCount(left.size) }
                    .view,
            )
            body.addView(sheetSection(app, "Other"))
            body.addView(
                SheetOption(app, R.drawable.wna_checklist, "Pick chapters…", "One by one, or a range, from your bookmark") {
                    sheet.dismissThen { showChapterSelectionFor(this, storyId, SelectionPurpose.DOWNLOAD) }
                }.view,
            )
            sheet = showBottomSheet(app, "Download text", "${story.title} · ${"%,d".format(left.size)} not downloaded", body)
        }
    }

    /** Audio: generate from the listening place, free up space, or Pick chapters…; from Listen it also opens the novel. */
    fun showAudio(
        storyId: String,
        fromListen: Boolean = false,
    ) {
        with(host) {
            val story = repository.story(storyId) ?: return
            val screen = navigator.current.stableKey
            scope.launch {
                val position = runCatching { repository.getTtsStoryPosition(story.id) }.getOrNull()
                val listenIndex = DetailsActionPlanning.listenPlace(story, position)?.number?.minus(1)
                // The read is quick, but the screen may have changed meanwhile; a sheet over another screen would be lost.
                if (navigator.current.stableKey != screen || app.isFinishing || app.isDestroyed) return@launch
                buildAudio(story, listenIndex, fromListen)
            }
        }
    }

    @Suppress("LongMethod") // One sheet, its options in reading order.
    private fun buildAudio(
        story: Story,
        listenIndex: Int?,
        fromListen: Boolean,
    ) {
        with(host) {
            val chapters = story.chapters
            val audio = chapterAudioInfo(narration, story)
            val bookmark = bookmarkIndexOf(story)
            val anchor = ChapterActionPlanning.anchorIndex(bookmark, listenIndex ?: -1)
            val anchorName = if (anchor == bookmark) "your bookmark" else "where you’re listening"
            val summary = ChapterActionPlanning.summary(chapters, bookmark, audio)
            val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            lateinit var sheet: BottomSheetHandle
            val colors = ThemeManager.colors

            body.addView(sheetSection(app, "Generate", first = true))
            val from = listenIndex ?: bookmark.coerceAtLeast(0)
            val nextIds = {
                ChapterActionPlanning.nextToGenerate(
                    chapters,
                    from,
                    ChapterSheetMemory.generateNext,
                    audio,
                    TtsContinueListening::isPlayable,
                )
            }
            val generate =
                SheetOption(
                    app,
                    R.drawable.wna_headphones,
                    if (listenIndex != null) "The next chapters from where you’re listening" else "The next chapters from your bookmark",
                    "From Ch. ${from + 1}; skips ones that already have audio",
                    iconTint = colors.secondary,
                ) {
                    val ids = nextIds()
                    if (ids.isEmpty()) return@SheetOption
                    sheet.dismissThen { queueNarration(story.id, ids) }
                }
            generate.addBelow(
                makeStepper(
                    app,
                    ChapterSheetMemory.generateNext.toDouble(),
                    StepperSpec(1.0, 1.0, MAX_STEPPER, label = "Chapters to generate"),
                ) {
                    ChapterSheetMemory.generateNext = it.toInt()
                    generate.setCount(nextIds().size)
                    null
                }.view,
            )
            generate.setCount(nextIds().size)
            body.addView(generate.view)

            body.addView(sheetSection(app, "Free up space"))

            fun deleteOption(
                option: SheetOption,
                ids: () -> List<String>,
                keepNote: String?,
            ) {
                option.onClick = {
                    val chosen = ids()
                    if (chosen.isNotEmpty()) sheet.dismissThen { confirmDelete(story, chosen, audio, keepNote) }
                }
                option.setCount(ids().size, sizeLabel(ChapterActionPlanning.bytesOf(ids(), audio)))
            }
            val heardIds = { ChapterActionPlanning.heard(chapters, anchor, audio) }
            val heard =
                SheetOption(
                    app,
                    R.drawable.wna_bookmark,
                    "What you already heard",
                    if (anchor >
                        0
                    ) {
                        "Everything before $anchorName (Ch. ${anchor + 1})"
                    } else {
                        "Needs a bookmark or listening place past Ch. 1"
                    },
                    iconTint = colors.bookmark,
                )
            deleteOption(heard, heardIds, if (anchor >= 0) "Audio from Ch. ${anchor + 1} on stays" else null)
            body.addView(heard.view)

            val breakIds = { ChapterActionPlanning.breakDeletions(chapters, anchor, ChapterSheetMemory.keepOnBreak, audio) }
            val keepFrom = anchor + 1
            val pause =
                SheetOption(
                    app,
                    R.drawable.wna_pause,
                    "Taking a break: keep only the next few",
                    if (anchor >= 0) {
                        "Keeps the chapters from $anchorName (Ch. $keepFrom) so you can pick up instantly; clears the rest of the queue"
                    } else {
                        "Needs a bookmark or a listening place"
                    },
                )
            deleteOption(pause, breakIds, null)
            pause.onClick = {
                val chosen = breakIds()
                val keep = ChapterSheetMemory.keepOnBreak
                if (chosen.isNotEmpty()) {
                    sheet.dismissThen {
                        confirmDelete(
                            story,
                            chosen,
                            audio,
                            ChapterActionPlanning
                                .rangeLabel((keepFrom - 1 until minOf(keepFrom - 1 + keep, chapters.size)).toList())
                                .replace("Ch.", "Keeps Ch."),
                        )
                    }
                }
            }
            pause.addBelow(
                makeStepper(app, ChapterSheetMemory.keepOnBreak.toDouble(), StepperSpec(1.0, 1.0, MAX_KEEP, label = "Chapters to keep")) {
                    ChapterSheetMemory.keepOnBreak = it.toInt()
                    pause.setCount(breakIds().size, sizeLabel(ChapterActionPlanning.bytesOf(breakIds(), audio)))
                    null
                }.view,
            )
            body.addView(pause.view)

            val allIds = { ChapterActionPlanning.allAudio(chapters, audio) }
            val all =
                SheetOption(
                    app,
                    R.drawable.wna_delete,
                    "Delete all of this novel’s audio",
                    if (summary.pending > 0) "Every generated chapter, and the ${summary.pending} queued" else "Every generated chapter",
                    danger = true,
                )
            deleteOption(all, allIds, null)
            body.addView(all.view)

            body.addView(sheetSection(app, "Other"))
            body.addView(
                SheetOption(app, R.drawable.wna_checklist, "Pick chapters…", "Generate or delete exactly the ones you choose") {
                    sheet.dismissThen { showChapterSelectionFor(this, story.id, SelectionPurpose.AUDIO) }
                }.view,
            )
            if (fromListen) {
                body.addView(
                    SheetOption(app, R.drawable.wna_menu_book, "Open the novel", "Its page, with every chapter") {
                        sheet.dismissThen { showStoryChapters(story.id) }
                    }.view,
                )
            }
            val subtitle =
                listOf(
                    story.title,
                    NarrationScreenPlanning.chapters(summary.ready),
                    NarrationPlanning.formatBytes(summary.bytes),
                ).joinToString(" · ")
            sheet = showBottomSheet(app, "Audio", subtitle, body)
        }
    }

    /**
     * Asks before deleting the audio of [ids]: what goes (generated chapters and their size, queued
     * ones leaving the queue) and what stays. The delete itself waits behind an Undo bar.
     */
    fun confirmDelete(
        story: Story,
        ids: List<String>,
        audio: Map<String, ChapterAudioInfo>,
        keepNote: String?,
        onConfirmed: () -> Unit = {},
    ) {
        with(host) {
            val pending = ChapterActionPlanning.pendingOf(ids, audio)
            val ready = ids.size - pending
            val bytes = ChapterActionPlanning.bytesOf(ids, audio)
            val withPack = ids.filterTo(HashSet()) { audio[it]?.hasPack == true }
            val indexes = story.chapters.mapIndexedNotNull { index, chapter -> index.takeIf { chapter.id in withPack } }
            val lines =
                listOfNotNull(
                    if (ready >
                        0
                    ) {
                        "${NarrationScreenPlanning.chapters(
                            ready,
                        )} of audio (${ChapterActionPlanning.rangeLabel(indexes)}) · ${NarrationPlanning.formatBytes(bytes)}"
                    } else {
                        null
                    },
                    if (pending > 0) "$pending queued leave the generation queue" else null,
                    keepNote,
                    "Text and bookmark stay as they are",
                )
            confirm(
                "Delete audio?\n\n" + lines.joinToString("\n") { "• $it" },
                confirmLabel = if (bytes > 0) "Delete ${NarrationPlanning.formatBytes(bytes)}" else "Delete",
            ) {
                val freed = if (bytes > 0) " · ${NarrationPlanning.formatBytes(bytes)}" else ""
                deleteWithUndo(mapOf(story.id to ids), "Deleting audio of ${NarrationScreenPlanning.chapters(ids.size)}$freed")
                onConfirmed()
            }
        }
    }

    /** Deletes the audio of [chapters] (chapter ids by story) after a few seconds unless Undo is tapped; nothing is removed before then. */
    fun deleteWithUndo(
        chapters: Map<String, List<String>>,
        message: String,
    ) {
        with(host) {
            val coordinator = narration
            val appScope = narrationScope
            val previous = PendingAudioDelete.current
            // The same chapters again (a double tap) keep the delete already waiting and its Undo.
            if (previous != null && previous.chapters == chapters && !previous.claimed.get()) return
            // A different delete still waiting runs now, so two in a row both happen.
            if (previous != null && previous.claimed.compareAndSet(false, true)) appScope.launch { previous.commit(coordinator) }
            val pending = PendingAudioDelete(chapters)
            PendingAudioDelete.current = pending
            appScope.launch {
                delay(UNDO_WINDOW_MS)
                if (pending.claimed.compareAndSet(false, true)) pending.commit(coordinator)
            }
            app.showUndoBar(message, UNDO_WINDOW_MS) {
                // Undo wins only if the delete has not started; otherwise say so instead of claiming it was kept.
                if (pending.claimed.compareAndSet(
                        false,
                        true,
                    )
                ) {
                    toast("Kept the audio")
                } else {
                    toast("Too late: that audio is already deleted")
                }
            }
        }
    }
}

/**
 * A delete waiting behind the Undo bar. [claimed] decides, once, whether the timer (or a newer
 * delete) runs it or Undo cancels it; the delete then runs to the end even if its scope is cancelled.
 */
private class PendingAudioDelete(
    val chapters: Map<String, List<String>>,
) {
    val claimed = AtomicBoolean(false)

    suspend fun commit(coordinator: NarrationCoordinator) =
        withContext(NonCancellable) {
            chapters.forEach { (storyId, ids) ->
                runCatching { coordinator.deleteAudio(storyId, ids) }.onFailure { Timber.e(it, "Could not delete the audio") }
            }
        }

    companion object {
        /** The newest delete started; read and written on the main thread. */
        var current: PendingAudioDelete? = null
    }
}

/** A size beside an option's count; none when there is nothing to free. */
private fun sizeLabel(bytes: Long): String? = if (bytes > 0) NarrationPlanning.formatBytes(bytes) else null

internal fun bookmarkIndexOf(story: Story): Int = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1

/** A sheet heading in the faint label style. */
private fun sheetSection(
    context: Context,
    title: String,
    first: Boolean = false,
): View =
    makeText(context, title.uppercase(), Type.LABEL_SMALL, ThemeManager.colors.faint).apply {
        letterSpacing = LABEL_LETTER_SPACING
        setPadding(context.dp(Spacing.XS), context.dp(if (first) Spacing.XS else Spacing.LG), 0, context.dp(Spacing.SM))
    }

/**
 * One option card in a sheet: an icon tile, title and subtitle, the count it touches (and size)
 * on the right, and optional controls below. A count of 0 dims it and ignores taps.
 */
private class SheetOption(
    context: Context,
    iconRes: Int,
    title: String,
    subtitle: String,
    iconTint: Int = ThemeManager.colors.primary,
    danger: Boolean = false,
    var onClick: () -> Unit = {},
) {
    private val colors = ThemeManager.colors
    private val count = makeText(context, "", Type.TITLE_SMALL, colors.onSurface).apply { gravity = Gravity.END }
    private val extra = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { gravity = Gravity.END }
    private val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val view: LinearLayout

    init {
        val radius = context.dp(OPTION_RADIUS_DP).toFloat()
        val tile =
            FrameLayout(context).apply {
                background = roundedBg(if (danger) colors.errorContainer else colors.elevation4, context.dp(Spacing.MD).toFloat())
                addView(
                    ImageView(
                        context,
                    ).apply { setImageDrawable(context.tintedIcon(iconRes, if (danger) colors.onErrorContainer else iconTint)) },
                    FrameLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP), Gravity.CENTER),
                )
            }
        texts.addView(makeText(context, title, Type.TITLE_SMALL, if (danger) colors.error else colors.onSurface))
        texts.addView(
            makeText(context, subtitle, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, context.dp(2), 0, 0) },
        )
        val counts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(context.dp(Spacing.SM), 0, 0, 0)
                addView(count)
                addView(extra)
            }
        view =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dp(Spacing.MD + 2), context.dp(Spacing.MD + 2), context.dp(Spacing.MD + 2), context.dp(Spacing.MD + 2))
                background = ripple(strokeBg(colors.elevation3, radius, colors.line, context.dp(1)), radius, colors.onSurface)
                isClickable = true
                isFocusable = true
                addView(tile, LinearLayout.LayoutParams(context.dp(TILE_DP), context.dp(TILE_DP)))
                addView(
                    texts,
                    LinearLayout
                        .LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply { marginStart = context.dp(Spacing.MD + 2) },
                )
                addView(counts)
                setOnClickListener { onClick() }
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(Spacing.SM)
                    }
            }
    }

    /** The count (and size) on the right; 0 dims the option. */
    fun setCount(
        value: Int,
        size: String? = null,
    ) {
        count.text = "%,d".format(value)
        extra.text = size.orEmpty()
        extra.visibility = if (size == null) View.GONE else View.VISIBLE
        val enabled = value > 0
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** Controls under the subtitle (a stepper); taps on them do not run the option. */
    fun addBelow(control: View) {
        control.isClickable = true
        texts.addView(
            control,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = control.context.dp(Spacing.SM)
            },
        )
    }
}

private const val MAX_STEPPER = 500.0
private const val MAX_KEEP = 100.0
private const val UNDO_WINDOW_MS = 6_000L
private const val OPTION_RADIUS_DP = 16
private const val TILE_DP = 40
private const val ICON_DP = 22
private const val LABEL_LETTER_SPACING = 0.08f
