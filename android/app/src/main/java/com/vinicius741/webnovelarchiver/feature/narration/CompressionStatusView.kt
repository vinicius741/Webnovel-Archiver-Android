package com.vinicius741.webnovelarchiver.feature.narration

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionText
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import java.util.Date

/**
 * What the background audio compression is doing: a headline, a detail line and a progress bar.
 * Gone while there is nothing to say; [bind] it on every status change.
 */
internal class CompressionStatusView(
    private val context: Context,
) {
    private val colors = ThemeManager.colors
    private val headline = makeText(context, "", Type.BODY_MEDIUM, colors.onSurface)
    private val detail = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val bar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(colors.secondary)
            progressBackgroundTintList = ColorStateList.valueOf(colors.elevation4)
        }

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(headline)
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(BAR_DP)).apply {
                    topMargin = context.dp(BAR_GAP_DP)
                    bottomMargin = context.dp(BAR_GAP_DP)
                },
            )
            addView(detail)
            contentDescription = "Audio compression status"
        }

    fun bind(
        status: NarrationCompressionStatus,
        totalBytes: Long,
    ) {
        val lines = NarrationCompressionText.describe(status, totalBytes) { DateFormat.getTimeFormat(context).format(Date(it)) }
        view.visibility = if (lines == null) View.GONE else View.VISIBLE
        if (lines == null) return
        headline.text = lines.headline
        detail.text = lines.detail
        val fraction = status.fraction
        val working = status.phase != NarrationCompressionStatus.Phase.DONE
        bar.visibility = if (working && fraction != null) View.VISIBLE else View.GONE
        bar.progress = ((fraction ?: 0f) * PROGRESS_MAX).toInt()
    }

    private companion object {
        const val PROGRESS_MAX = 1000
        const val BAR_DP = 6
        const val BAR_GAP_DP = 6
    }
}
