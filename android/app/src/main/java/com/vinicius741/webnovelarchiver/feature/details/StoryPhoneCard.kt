package com.vinicius741.webnovelarchiver.feature.details

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Overview's "On this phone" card: how much of the novel is downloaded and how much has
 * audio, each with a bar (bookmark pin on top) and a button for its sheet (Text, Audio). Chapters
 * downloading and the one generating pulse on the bars; a line under the audio bar follows the
 * generating chapter. The page's renderer passes download events to [render]; the card follows
 * the generation queue and published audio itself while it is attached.
 */
internal class StoryPhoneCard(
    private val host: ScreenHost,
    private var story: Story,
    private var statuses: Map<String, DownloadJobStatus>,
) {
    private val context = host.app
    private val colors = ThemeManager.colors
    private val total = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val textTitle = makeText(context, "", Type.TITLE_SMALL, colors.onSurface)
    private val textSub = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val textBar = ChapterCoverageBar(context)
    private val downloadLine = makeText(context, "", Type.BODY_SMALL, colors.primary)
    private val audioTitle = makeText(context, "", Type.TITLE_SMALL, colors.onSurface)
    private val audioSub = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val audioBar = ChapterCoverageBar(context)
    private val liveLine = makeText(context, "", Type.BODY_SMALL, colors.secondary)
    private var audio: Map<String, ChapterAudioInfo> = emptyMap()
    private var observer: Job? = null
    val view: View

    init {
        val radius = context.dp(CARD_RADIUS_DP).toFloat()
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    makeText(context, "ON THIS PHONE", Type.LABEL_SMALL, colors.primary).apply { letterSpacing = LABEL_LETTER_SPACING },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(total)
            }
        view =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.LG), context.dp(Spacing.LG), context.dp(Spacing.LG), context.dp(Spacing.LG))
                background = strokeBg(colors.elevation1, radius, colors.line, context.dp(1))
                addView(header)
                addView(
                    row(R.drawable.wna_article, colors.primary, textTitle, textSub, "Text", R.drawable.wna_download) {
                        ChapterSheets(host).showText(story.id)
                    },
                    matchWidth(Spacing.MD),
                )
                addView(
                    textBar,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, textBar.layoutParams.height).apply {
                        topMargin = context.dp(Spacing.SM + 2)
                    },
                )
                addView(downloadLine, matchWidth(Spacing.SM))
                addView(
                    row(R.drawable.wna_graphic_eq, colors.secondary, audioTitle, audioSub, "Audio", R.drawable.wna_tune) {
                        ChapterSheets(host).showAudio(story.id)
                    },
                    matchWidth(Spacing.LG),
                )
                addView(
                    audioBar,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, audioBar.layoutParams.height).apply {
                        topMargin = context.dp(Spacing.SM + 2)
                    },
                )
                addView(liveLine, matchWidth(Spacing.SM))
                addView(legend(), matchWidth(Spacing.MD))
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(Spacing.MD)
                        bottomMargin = context.dp(Spacing.SM)
                    }
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = observeAudio()

                        override fun onViewDetachedFromWindow(v: View) {
                            observer?.cancel()
                            observer = null
                        }
                    },
                )
            }
        audio = chapterAudioInfo(host.narration, story)
        renderAll()
    }

    /** A download event: the story's chapters and their download jobs changed. */
    fun render(
        latest: Story,
        latestStatuses: Map<String, DownloadJobStatus>,
    ) {
        story = latest
        statuses = latestStatuses
        audio = chapterAudioInfo(host.narration, story)
        renderAll()
    }

    private fun renderAll() {
        renderText()
        renderAudio()
        renderLive()
    }

    private fun renderText() {
        val chapters = story.chapters
        val downloading = statuses.filterValues { it == DownloadJobStatus.Downloading }.keys
        val waiting = statuses.filterValues { it == DownloadJobStatus.Pending || it == DownloadJobStatus.Paused }.keys
        val done = chapters.count { it.downloaded }
        val left = chapters.size - done
        textTitle.text = "${"%,d".format(done)} of ${"%,d".format(chapters.size)} downloaded"
        textSub.text = if (left > 0) "${"%,d".format(left)} to go" else "Everything is downloaded"
        textBar.bindStates(
            ChapterActionPlanning.textStates(chapters, downloading, waiting),
            intArrayOf(0, colors.outline, colors.primary, colors.primaryContainer),
            ChapterActionPlanning.TEXT_DOWNLOADING.toInt(),
            bookmarkFraction(),
            "$done of ${chapters.size} chapters downloaded" + bookmarkSpoken(),
        )
        val active = (downloading + waiting).count { id -> chapters.any { it.id == id && !it.downloaded } }
        downloadLine.text = "Downloading · ${"%,d".format(active)} left"
        downloadLine.visibility = if (active > 0) View.VISIBLE else View.GONE
    }

    private fun renderAudio() {
        val chapters = story.chapters
        val summary = ChapterActionPlanning.summary(chapters, bookmarkIndexOf(story), audio)
        audioTitle.text = if (summary.ready > 0) "${NarrationScreenPlanning.chapters(summary.ready)} of audio" else "No audio yet"
        audioSub.text =
            if (summary.ready > 0) {
                listOfNotNull(
                    NarrationPlanning.formatBytes(summary.bytes),
                    NarrationPlanning.formatDuration(summary.durationMs),
                    summary.heard.takeIf { it > 0 }?.let { "$it already heard" },
                ).joinToString(" · ")
            } else {
                "Generate chapters to listen offline"
            }
        total.text = NarrationPlanning.formatBytes(summary.bytes)
        total.visibility = if (summary.bytes > 0) View.VISIBLE else View.GONE
        audioBar.bindStates(
            ChapterActionPlanning.audioStates(chapters, audio),
            intArrayOf(0, colors.primary, colors.secondaryContainer, colors.secondary),
            ChapterActionPlanning.AUDIO_GENERATING.toInt(),
            bookmarkFraction(),
            "${summary.ready} chapters with audio, ${summary.pending} queued" + bookmarkSpoken(),
        )
    }

    /** "Generating Ch. 230 · 11% · 6 more queued", or how many wait behind other novels. */
    private fun renderLive() {
        val progress = host.narration.progress.value
        val number =
            progress?.job?.takeIf { it.storyId == story.id }?.let { job -> story.chapters.indexOfFirst { it.id == job.chapterId } + 1 } ?: 0
        // A chapter of another story, or one no longer in this story, shows no generating line.
        val job = progress?.job?.takeIf { number > 0 }
        val queued = audio.count { (id, info) -> info.state == ChapterAudioState.QUEUED && id != job?.chapterId }
        liveLine.text =
            when {
                job != null -> {
                    val percent =
                        if (progress.preparing ||
                            progress.total <= 0
                        ) {
                            "starting"
                        } else {
                            NarrationScreenPlanning.percent(progress.done.toFloat() / progress.total)
                        }
                    "Generating Ch. $number · $percent" + if (queued > 0) " · $queued more queued" else ""
                }
                queued > 0 && host.narration.queueState.value.paused -> "$queued queued · generation is paused"
                queued > 0 -> "$queued queued, waiting for other novels"
                else -> ""
            }
        liveLine.setTextColor(if (job != null) colors.secondary else colors.onSurfaceVariant)
        liveLine.visibility = if (liveLine.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Follows the queue and new packs (rebuilding the audio part) and progress (the live line only). */
    private fun observeAudio() {
        observer?.cancel()
        val coordinator = host.narration
        val running = coordinator.progress.map { it?.takeUnless { p -> p.preparing }?.job?.id }.distinctUntilChanged()
        observer =
            host.scope.launch {
                launch {
                    combine(
                        coordinator.queueState.map { it.jobs }.distinctUntilChanged(),
                        coordinator.packsRevision,
                        running,
                    ) { _, _, _ -> Unit }
                        .collect {
                            audio = chapterAudioInfo(host.narration, story)
                            renderAudio()
                            renderLive()
                        }
                }
                launch { coordinator.progress.collect { renderLive() } }
            }
    }

    private fun bookmarkFraction(): Float? {
        val index = bookmarkIndexOf(story)
        return if (index < 0 || story.chapters.isEmpty()) null else (index + 0.5f) / story.chapters.size
    }

    private fun bookmarkSpoken(): String {
        val index = bookmarkIndexOf(story)
        return if (index >= 0) ", bookmark at chapter ${index + 1}" else ""
    }

    private fun row(
        iconRes: Int,
        iconTint: Int,
        title: TextView,
        sub: TextView,
        action: String,
        actionIcon: Int,
        onAction: () -> Unit,
    ): View {
        val tile =
            FrameLayout(context).apply {
                background = roundedBg(colors.elevation3, context.dp(Spacing.MD).toFloat())
                addView(
                    ImageView(context).apply { setImageDrawable(context.tintedIcon(iconRes, iconTint)) },
                    FrameLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP), Gravity.CENTER),
                )
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub.apply { setPadding(0, context.dp(2), 0, 0) })
            }
        val button =
            makeButton(context, action, Btn.SURFACE, actionIcon) { onAction() }.apply {
                minHeight = context.dp(BUTTON_HEIGHT_DP)
                minimumHeight = context.dp(BUTTON_HEIGHT_DP)
                setPadding(context.dp(Spacing.MD), 0, context.dp(Spacing.MD + 2), 0)
                contentDescription = if (action == "Text") "Download text" else "Audio options"
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tile, LinearLayout.LayoutParams(context.dp(TILE_DP), context.dp(TILE_DP)).apply { marginEnd = context.dp(Spacing.MD) })
            addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = context.dp(Spacing.SM)
                },
            )
            // The icon tile only where the titles keep room for one line (not in the inner screen's narrow hero pane).
            addOnLayoutChangeListener { row, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                val show = right - left >= row.context.dp(WIDE_ROW_DP)
                if ((tile.visibility == View.VISIBLE) != show) row.post { tile.visibility = if (show) View.VISIBLE else View.GONE }
            }
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(BUTTON_HEIGHT_DP)))
        }
    }

    /** ■ Audio ■ Generating ■ Queued ■ Downloaded ▮ Bookmark, as one wrapping line. */
    private fun legend(): TextView {
        val text = SpannableStringBuilder()
        listOf(
            colors.primary to "Audio",
            colors.secondary to "Generating",
            colors.secondaryContainer to "Queued",
            colors.outline to "Downloaded",
            colors.bookmark to "Bookmark",
        ).forEachIndexed { index, (color, label) ->
            if (index > 0) text.append("   ")
            val start = text.length
            text.append("■")
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ").append(label)
        }
        return makeText(context, text, Type.LABEL_SMALL, colors.onSurfaceVariant).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setLineSpacing(context.dp(2).toFloat(), 1f)
        }
    }

    private fun matchWidth(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(top)
        }
}

private const val CARD_RADIUS_DP = 18
private const val TILE_DP = 40

/** Row width from which the icon tile shows beside the titles. */
private const val WIDE_ROW_DP = 340
private const val ICON_DP = 22
private const val BUTTON_HEIGHT_DP = 38
private const val LABEL_LETTER_SPACING = 0.08f
