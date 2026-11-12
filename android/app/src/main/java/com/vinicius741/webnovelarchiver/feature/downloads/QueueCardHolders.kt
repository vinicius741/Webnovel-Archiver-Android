package com.vinicius741.webnovelarchiver.feature.downloads

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.download.SourceCheckPhase
import com.vinicius741.webnovelarchiver.feature.settings.makeEyebrow
import com.vinicius741.webnovelarchiver.feature.settings.makePill
import com.vinicius741.webnovelarchiver.feature.settings.tintedCardBackground
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// The Downloads list's non-novel rows: the summary card, the site-check card, the failures card,
// and the section headers. Novel cards and chapter rows live in QueueStoryHolders.kt.

internal abstract class QueueHolder(
    view: View,
) : RecyclerView.ViewHolder(view) {
    abstract fun bind(item: QueueItem)
}

/** Overall percent, chapters left, and the Live / Paused / Done pill over a thick progress bar. */
internal class QueueSummaryHolder(
    context: Context,
) : QueueHolder(makeCard(context).withQueueRowParams(bottom = Spacing.MD)) {
    private val percent =
        makeText(context, "", Type.HEADLINE, ThemeManager.colors.onSurface).apply {
            textSize = PERCENT_SP
            fontFeatureSettings = "tnum"
        }
    private val detail = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    private val pillSlot = LinearLayout(context)
    private val progress = QueueProgressBar(context)

    init {
        val card = itemView as LinearLayout
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(percent)
                        addView(detail, gap(context, 2))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(pillSlot)
            },
        )
        card.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(THICK_BAR_DP)).apply {
                topMargin =
                    context.dp(Spacing.MD)
            },
        )
    }

    override fun bind(item: QueueItem) {
        val summary = (item as QueueSummaryItem).summary
        val colors = ThemeManager.colors
        percent.text = "${summary.percent}%"
        detail.text = summary.detail
        progress.set(summary.fraction, colors.primary)
        pillSlot.removeAllViews()
        val pill =
            when (summary.state) {
                QueueLiveState.LIVE -> makePill(itemView.context, "Live", colors.primaryContainer, colors.onPrimaryContainer, dot = true)
                QueueLiveState.PAUSED -> makePill(itemView.context, "Paused", colors.secondaryContainer, colors.onSecondaryContainer)
                QueueLiveState.DONE -> makePill(itemView.context, "Done", colors.tertiaryContainer, colors.tertiary)
            }
        pillSlot.addView(pill)
        itemView.contentDescription = "${summary.percent} percent downloaded, ${summary.detail}"
    }
}

/** One blocked site: verification needed, still blocked after a check, or verified and resuming. */
internal class QueueCheckHolder(
    context: Context,
    actions: QueueListActions,
) : QueueHolder(
        LinearLayout(context)
            .apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(Spacing.LG)
                setPadding(pad, pad, pad, pad)
            }.withQueueRowParams(bottom = Spacing.MD),
    ) {
    private val icon = ImageView(context)
    private val eyebrowSlot = LinearLayout(context)
    private val title = makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply { textSize = TITLE_SP }
    private val body = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var bound: SourceCheckCard? = null
    private val primary = makeButton(context, "Verify now", Btn.FILLED) { bound?.let { actions.onCheck(it, trouble = false) } }
    private val trouble = makeButton(context, "Having trouble?", Btn.TEXT) { bound?.let { actions.onCheck(it, trouble = true) } }

    init {
        val card = itemView as LinearLayout
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    icon,
                    LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply {
                        marginEnd =
                            context.dp(Spacing.MD + 2)
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(eyebrowSlot)
                        addView(title)
                        addView(body, gap(context, 2))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )
        buttons.addView(primary)
        buttons.addView(
            trouble,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    context.dp(Spacing.SM)
            },
        )
        card.addView(buttons, gap(context, Spacing.MD))
    }

    override fun bind(item: QueueItem) {
        val check = item as QueueCheckItem
        val card = check.card
        bound = card
        val context = itemView.context
        val colors = ThemeManager.colors
        val chapters = plural(card.chapterCount, "chapter")
        val (tint, iconRes, eyebrow) =
            when (card.phase) {
                SourceCheckPhase.NEEDS_CHECK -> Triple(colors.primary, R.drawable.wna_shield, "Verification needed")
                SourceCheckPhase.STILL_BLOCKED -> Triple(colors.error, R.drawable.wna_shield, "Still blocked")
                SourceCheckPhase.VERIFIED -> Triple(colors.tertiary, R.drawable.wna_verified_user, null)
            }
        itemView.background = tintedCardBackground(context, tint)
        icon.setImageDrawable(context.tintedIcon(iconRes, tint))
        eyebrowSlot.removeAllViews()
        eyebrow?.let { eyebrowSlot.addView(makeEyebrow(context, it, tint)) }
        when (card.phase) {
            SourceCheckPhase.NEEDS_CHECK -> {
                title.text = "${check.siteName} needs a quick check"
                body.text = "${plural(card.storyCount, "novel")} · $chapters waiting. Other sites keep going."
                primary.text = "Verify now"
            }
            SourceCheckPhase.STILL_BLOCKED -> {
                title.text = "${check.siteName} is still blocked"
                body.text = "The check didn't finish, so $chapters stay paused."
                primary.text = "Try again"
            }
            SourceCheckPhase.VERIFIED -> {
                title.text = "${check.siteName} verified"
                body.text = if (card.chapterCount > 0) "Resuming $chapters…" else "Downloads can continue."
            }
        }
        buttons.visibility = if (card.phase == SourceCheckPhase.VERIFIED) View.GONE else View.VISIBLE
    }
}

/** Failed chapters the user can retry, with one Retry all. */
internal class QueueFailedHolder(
    context: Context,
    actions: QueueListActions,
) : QueueHolder(
        LinearLayout(context)
            .apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = context.dp(Spacing.LG)
                setPadding(pad, pad, context.dp(Spacing.MD), pad)
                background = tintedCardBackground(context, ThemeManager.colors.error)
            }.withQueueRowParams(bottom = Spacing.MD),
    ) {
    private val title =
        makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.error).apply {
            textSize = TITLE_SP
        }

    init {
        val row = itemView as LinearLayout
        row.addView(
            ImageView(context).apply { setImageDrawable(context.tintedIcon(R.drawable.wna_error, ThemeManager.colors.error)) },
            LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply { marginEnd = context.dp(Spacing.MD + 2) },
        )
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(
                    makeText(
                        context,
                        "Usually a network hiccup or the site limiting requests.",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    ),
                    gap(context, 2),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Spacing.SM) },
        )
        row.addView(makeButton(context, "Retry all", Btn.ERROR) { actions.onRetryFailed() })
    }

    override fun bind(item: QueueItem) {
        val count = (item as QueueFailedItem).count
        title.text = if (count == 1) "1 chapter couldn't download" else "$count chapters couldn't download"
    }
}

/** A gold section label; Up next hints at dragging, Finished folds and offers Clear. */
internal class QueueHeaderHolder(
    context: Context,
    actions: QueueListActions,
) : QueueHolder(
        LinearLayout(context)
            .apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(HEADER_HEIGHT_DP)
                setPadding(context.dp(Spacing.XS), 0, 0, 0)
            }.withQueueRowParams(top = Spacing.SM, bottom = Spacing.XS),
    ) {
    private val row = itemView as LinearLayout
    private val title =
        makeText(context, "", Type.LABEL_MEDIUM, ThemeManager.colors.primary).apply {
            isAllCaps = true
            letterSpacing = LABEL_SPACING
        }
    private val chevron =
        ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_down, ThemeManager.colors.primary))
        }
    private val hintIcon =
        ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_drag_handle, ThemeManager.colors.faint))
        }
    private val hint = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    private val clear =
        makeText(context, "Clear", Type.LABEL_LARGE, ThemeManager.colors.primary).apply {
            val pad = context.dp(Spacing.SM)
            setPadding(pad + 2, pad, pad + 2, pad)
            background = selectableRipple(ThemeManager.colors.primary)
            contentDescription = "Clear finished"
            setOnClickListener { actions.onClearFinished() }
        }
    private var section: QueueSection? = null

    init {
        row.addView(title)
        row.addView(chevron, LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply { marginStart = context.dp(Spacing.XS) })
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(hintIcon, LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginEnd = context.dp(2) })
        row.addView(hint)
        row.addView(clear)
        row.setOnClickListener { if (section == QueueSection.FINISHED) actions.onToggleFinished() }
    }

    override fun bind(item: QueueItem) {
        val header = item as QueueHeaderItem
        section = header.section
        title.text = header.title
        val finished = header.section == QueueSection.FINISHED
        hint.text = header.hint.orEmpty()
        hint.visibility = if (header.hint == null) View.GONE else View.VISIBLE
        hint.setTextColor(if (finished) ThemeManager.colors.error else ThemeManager.colors.onSurfaceVariant)
        hintIcon.visibility = if (header.section == QueueSection.UP_NEXT && header.hint != null) View.VISIBLE else View.GONE
        chevron.visibility = if (header.collapsible) View.VISIBLE else View.GONE
        chevron.rotation = if (header.expanded) 180f else 0f
        clear.visibility = if (finished) View.VISIBLE else View.GONE
        row.isClickable = header.collapsible
        row.background = if (header.collapsible) selectableRipple(ThemeManager.colors.onSurface) else null
        row.contentDescription =
            if (header.collapsible) "${header.title}, ${if (header.expanded) "hide" else "show"} novels" else null
    }
}

/** A rounded progress line whose fill color follows the row's state. */
internal class QueueProgressBar(
    context: Context,
) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeManager.colors.elevation4 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(
        value: Float,
        color: Int,
    ) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == fraction && fill.color == color) return
        fraction = clamped
        fill.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, track)
        if (fraction <= 0f) return
        rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
    }
}

internal fun queueToneColor(tone: QueueTone): Int =
    when (tone) {
        QueueTone.NORMAL -> ThemeManager.colors.onSurfaceVariant
        QueueTone.ACTIVE -> ThemeManager.colors.primary
        QueueTone.WARNING -> ThemeManager.colors.secondary
        QueueTone.ERROR -> ThemeManager.colors.error
        QueueTone.DONE -> ThemeManager.colors.tertiary
    }

internal fun plural(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"

internal fun gap(
    context: Context,
    topDp: Int,
) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
    topMargin = context.dp(topDp)
}

internal fun <T : View> T.withQueueRowParams(
    top: Int = 0,
    bottom: Int,
): T =
    apply {
        layoutParams =
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(top)
                bottomMargin = context.dp(bottom)
            }
    }

/** One line, ellipsized at the end. */
internal fun TextView.singleLine(): TextView =
    apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

private const val PERCENT_SP = 26f
private const val TITLE_SP = 15f
private const val THICK_BAR_DP = 6
private const val ICON_DP = 24
private const val HEADER_HEIGHT_DP = 40
private const val LABEL_SPACING = 0.06f
