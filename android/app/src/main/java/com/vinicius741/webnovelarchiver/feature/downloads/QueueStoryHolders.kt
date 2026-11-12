package com.vinicius741.webnovelarchiver.feature.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.ui.IconButtonStyle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * A novel's card: drag handle (Up next), cover (tap to view it full screen), title, site and
 * progress, a colored status line, the one inline action, the menu, and a progress bar. Tapping the
 * rest of the card shows or hides its chapter rows, which continue the card below it.
 */
@SuppressLint("ClickableViewAccessibility")
internal class QueueStoryHolder(
    context: Context,
    actions: QueueListActions,
    touchHelper: ItemTouchHelper,
    private val bindCover: (ImageView, String) -> Unit,
) : QueueHolder(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.withQueueRowParams(bottom = Spacing.MD - 2)) {
    private val card = itemView as LinearLayout
    private val handle =
        context.iconButton(R.drawable.wna_drag_handle, "Drag to reorder", ThemeManager.colors.faint, IconButtonStyle.Compact)
    private val cover =
        makeCover(context, COVER_WIDTH_DP, COVER_HEIGHT_DP).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = context.dp(Spacing.MD)
            roundCorners(COVER_RADIUS_DP)
            clipToOutline = true
            contentDescription = "View cover"
        }
    private val title = makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply { textSize = TITLE_SP }.singleLine()
    private val subtitle = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).singleLine()
    private val status = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).singleLine()
    private val progress = QueueProgressBar(context)
    private var bound: QueueStoryItem? = null
    private val primary = context.iconButton(R.drawable.wna_pause, "Pause", ThemeManager.colors.onSurface, IconButtonStyle.Small)
    private val more =
        context.iconButton(
            R.drawable.wna_more_vert,
            "More options",
            ThemeManager.colors.onSurfaceVariant,
            IconButtonStyle.Small,
        )

    init {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle, gap(context, 2))
                addView(status, gap(context, 2))
            }
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM + 2), context.dp(Spacing.SM + 2), context.dp(Spacing.XS), context.dp(Spacing.SM + 2))
                addView(handle)
                addView(cover)
                addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(primary)
                addView(more)
            },
        )
        card.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(BAR_DP)).apply {
                setMargins(context.dp(Spacing.LG), 0, context.dp(Spacing.LG), context.dp(Spacing.MD))
            },
        )
        card.isClickable = true
        card.isFocusable = true
        card.foreground = selectableRipple(ThemeManager.colors.onSurface)
        card.setOnClickListener { bound?.takeIf { it.expandable }?.let { actions.onToggleStory(it.storyId) } }
        primary.setOnClickListener { bound?.let(actions::onStoryPrimary) }
        more.setOnClickListener { bound?.let(actions::onStoryMenu) }
        handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && bound?.draggable == true) touchHelper.startDrag(this)
            false
        }
    }

    override fun bind(item: QueueItem) {
        val story = item as QueueStoryItem
        val previous = bound
        bound = story
        val colors = ThemeManager.colors
        val context = itemView.context
        title.text = story.title
        subtitle.text = story.subtitle
        status.text = if (story.tone == QueueTone.ACTIVE) "● ${story.status}" else story.status
        status.setTextColor(queueToneColor(story.tone))
        val fraction = if (story.total > 0) story.completed.toFloat() / story.total else 0f
        progress.set(fraction, progressColor(story))
        handle.visibility = if (story.draggable) View.VISIBLE else View.GONE
        if (previous?.storyId != story.storyId) bindCover(cover, story.storyId)
        val (icon, label, tint) =
            when (story.primaryAction) {
                QueueAction.PAUSE -> Triple(R.drawable.wna_pause, "Pause", colors.onSurface)
                QueueAction.RESUME -> Triple(R.drawable.wna_play, "Resume", colors.primary)
                QueueAction.RETRY -> Triple(R.drawable.wna_refresh, "Retry failed chapters", colors.primary)
                QueueAction.REMOVE, QueueAction.CANCEL -> Triple(R.drawable.wna_close, "Remove from list", colors.onSurfaceVariant)
            }
        primary.setImageDrawable(context.tintedIcon(icon, tint))
        primary.contentDescription = "$label ${story.title}"
        card.background = storyCardBackground(context, story.expanded)
        (card.layoutParams as RecyclerView.LayoutParams).bottomMargin = context.dp(if (story.expanded) 0 else Spacing.MD - 2)
        card.contentDescription =
            if (story.expandable) "${story.title}, ${if (story.expanded) "hide" else "show"} chapters" else story.title
    }

    private fun progressColor(story: QueueStoryItem): Int {
        val colors = ThemeManager.colors
        return when {
            story.section == QueueSection.PAUSED -> colors.secondary
            story.section == QueueSection.FINISHED && story.tone == QueueTone.ERROR -> colors.error
            story.section == QueueSection.FINISHED -> colors.tertiary
            else -> colors.primary
        }
    }
}

/** One open chapter under its novel: status dot, title, status, and an inline retry for failures. */
internal class QueueChapterHolder(
    context: Context,
    actions: QueueListActions,
) : QueueHolder(
        LinearLayout(context)
            .apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(ROW_MIN_DP)
                setPadding(context.dp(Spacing.LG + 2), context.dp(2), context.dp(Spacing.SM), context.dp(2))
            }.withQueueRowParams(bottom = 0),
    ) {
    private val row = itemView as LinearLayout
    private val dot = View(context)
    private val title = makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply { textSize = CHAPTER_SP }.singleLine()
    private val status = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).singleLine()
    private var bound: QueueChapterItem? = null
    private val retry = context.iconButton(R.drawable.wna_refresh, "Retry", ThemeManager.colors.primary, IconButtonStyle.Compact)

    init {
        row.addView(dot, LinearLayout.LayoutParams(context.dp(DOT_DP), context.dp(DOT_DP)).apply { marginEnd = context.dp(Spacing.MD) })
        row.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Spacing.SM) },
        )
        row.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd =
                    context.dp(Spacing.XS)
            },
        )
        row.addView(retry)
        row.isClickable = true
        row.foreground = selectableRipple(ThemeManager.colors.onSurface)
        row.setOnClickListener { bound?.let(actions::onChapterMenu) }
        retry.setOnClickListener { bound?.let(actions::onChapterRetry) }
    }

    override fun bind(item: QueueItem) {
        val chapter = item as QueueChapterItem
        bound = chapter
        val context = itemView.context
        val colors = ThemeManager.colors
        title.text = chapter.title
        status.text = chapter.status
        val color = queueToneColor(chapter.tone)
        status.setTextColor(color)
        val radius = context.dp(DOT_DP / 2).toFloat()
        dot.background =
            when (DownloadJobStatus.parse(chapter.jobStatus)) {
                DownloadJobStatus.Pending, DownloadJobStatus.Paused ->
                    if (chapter.blocked) {
                        roundedBg(
                            color,
                            radius,
                        )
                    } else {
                        strokeBg(android.graphics.Color.TRANSPARENT, radius, colors.faint, context.dp(1))
                    }
                else -> roundedBg(color, radius)
            }
        retry.visibility = if (chapter.inlineRetry) View.VISIBLE else View.GONE
        row.background = chapterRowBackground(context, chapter.last)
        val params = row.layoutParams as RecyclerView.LayoutParams
        params.bottomMargin = context.dp(if (chapter.last) Spacing.MD - 2 else 0)
        row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, context.dp(if (chapter.last) Spacing.SM else 2))
        row.contentDescription = "${chapter.title}, ${chapter.status}"
    }
}

/** The card's surface; when its chapters are open the bottom corners square off to meet them. */
private fun storyCardBackground(
    context: Context,
    expanded: Boolean,
): Drawable {
    val colors = ThemeManager.colors
    val r = context.dp(ThemeManager.shapes.cardRadius).toFloat()
    val bottom = if (expanded) 0f else r
    return GradientDrawable().apply {
        cornerRadii = floatArrayOf(r, r, r, r, bottom, bottom, bottom, bottom)
        setColor(colors.elevation1)
        setStroke(context.dp(1).coerceAtLeast(1), colors.line)
    }
}

/**
 * The chapter rows' shared surface: a slightly darker band with side hairlines that continues the
 * novel's card; the [last] row closes it with rounded corners and a bottom hairline.
 */
private fun chapterRowBackground(
    context: Context,
    last: Boolean,
): Drawable {
    val colors = ThemeManager.colors
    val line = context.dp(1).coerceAtLeast(1)
    val r = if (last) context.dp(ThemeManager.shapes.cardRadius).toFloat() else 0f
    val radii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    val border =
        GradientDrawable().apply {
            cornerRadii = radii
            setColor(colors.line)
        }
    val fill =
        GradientDrawable().apply {
            cornerRadii = radii
            setColor(ColorUtils.blendARGB(colors.elevation1, colors.background, CHAPTER_SHADE))
        }
    return LayerDrawable(arrayOf(border, fill)).apply { setLayerInset(1, line, 0, line, if (last) line else 0) }
}

private const val COVER_WIDTH_DP = 40
private const val COVER_HEIGHT_DP = 56
private const val COVER_RADIUS_DP = 8f
private const val TITLE_SP = 15f
private const val CHAPTER_SP = 13.5f
private const val BAR_DP = 4
private const val DOT_DP = 8
private const val ROW_MIN_DP = 40
private const val CHAPTER_SHADE = 0.45f
