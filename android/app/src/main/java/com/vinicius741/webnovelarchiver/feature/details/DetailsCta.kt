package com.vinicius741.webnovelarchiver.feature.details

import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * The Overview's contextual actions ([DetailsActionPlanning]): Continue reading with a Listen
 * button beside it, and Download N remaining, which leads once the reader has caught up. While
 * chapters download, the banner above stands in for the download button. Patched in place by
 * live download events.
 */
internal fun ScreenHost.renderDetailsCta(
    slot: LinearLayout,
    story: Story,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    isBusy: Boolean,
) {
    slot.removeAllViews()
    val plan = DetailsActionPlanning.plan(story, StoryActionGuards.canQueueDownloads(story))
    val showDownload = plan.remaining > 0 && !summary.isActive
    val downloadEnabled = !isBusy
    val read = plan.read
    val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    val primary: Button? =
        when {
            plan.downloadFirst && showDownload ->
                makeButton(
                    app,
                    DetailsActionPlanning.downloadLabel(plan),
                    Btn.FILLED,
                    R.drawable.wna_download,
                ) { downloadRemaining(story.id) }
                    .also { if (!downloadEnabled) it.disable() }
            read != null ->
                makeButton(app, DetailsActionPlanning.readLabel(read), Btn.FILLED, R.drawable.wna_menu_book) {
                    showReader(story.id, read.chapterId)
                }
            else -> null
        }
    primary?.let {
        it.minHeight = dp(CTA_HEIGHT_DP)
        it.maxLines = 1
        it.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(it, LinearLayout.LayoutParams(0, dp(CTA_HEIGHT_DP), 1f))
    }
    if (read !=
        null
    ) {
        row.addView(
            listenButton(story.id, read.chapterId),
            LinearLayout.LayoutParams(dp(CTA_HEIGHT_DP), dp(CTA_HEIGHT_DP)).apply {
                marginStart =
                    dp(Spacing.SM + 2)
            },
        )
    }
    if (row.childCount > 0) slot.addView(row)
    val secondary: Button? =
        when {
            plan.downloadFirst && showDownload && read != null ->
                makeButton(app, DetailsActionPlanning.readLabel(read, short = true), Btn.SURFACE, R.drawable.wna_menu_book) {
                    showReader(story.id, read.chapterId)
                }
            !plan.downloadFirst && showDownload ->
                makeButton(
                    app,
                    DetailsActionPlanning.downloadLabel(plan),
                    Btn.SURFACE,
                    R.drawable.wna_download,
                ) { downloadRemaining(story.id) }.apply {
                    // Copper: a secondary download, distinct from the gold reading action.
                    val radius = dp(ThemeManager.shapes.buttonRadius).toFloat()
                    background =
                        ripple(roundedBg(ThemeManager.colors.secondaryContainer, radius), radius, ThemeManager.colors.onSecondaryContainer)
                    setTextColor(ThemeManager.colors.onSecondaryContainer)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        app.tintedIcon(R.drawable.wna_download, ThemeManager.colors.onSecondaryContainer),
                        null,
                        null,
                        null,
                    )
                    if (!downloadEnabled) disable()
                }
            else -> null
        }
    secondary?.let {
        slot.addView(
            it,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Spacing.SM + 2)
            },
        )
    }
}

/** Listens from the chapter Continue would open; plays only generated audio (see [playIfGenerated]). */
private fun ScreenHost.listenButton(
    storyId: String,
    chapterId: String,
): ImageView {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.buttonRadius).toFloat()
    return ImageView(app).apply {
        contentDescription = "Listen"
        setImageDrawable(app.tintedIcon(R.drawable.wna_headphones, colors.onPrimaryContainer))
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(roundedBg(colors.primaryContainer, radius), radius, colors.onPrimaryContainer)
        isClickable = true
        isFocusable = true
        setOnClickListener { playIfGenerated(storyId, chapterId) { TtsForegroundService.start(app, storyId, chapterId) } }
    }
}

/** Queues every chapter that is not downloaded yet, read fresh so a sync in between is included. */
private fun ScreenHost.downloadRemaining(storyId: String) {
    val latest = repository.story(storyId) ?: return
    queueDownload(latest, latest.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null })
}

private fun Button.disable() {
    isEnabled = false
    alpha = DISABLED_ALPHA
}

private const val CTA_HEIGHT_DP = 54
