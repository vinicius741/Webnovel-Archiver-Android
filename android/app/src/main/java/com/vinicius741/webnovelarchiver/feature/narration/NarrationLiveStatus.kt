package com.vinicius741.webnovelarchiver.feature.narration

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// The generation banner pinned to the top of both narration screens. It always says what is being
// generated, in which story, how far along and how fast, whichever screen the user came from, and
// updates in place on every sentence instead of rebuilding the screen.

internal class NarrationLiveStatus(
    host: ScreenHost,
    /** Hide the banner while nothing is queued (the story screen) instead of saying so. */
    private val hideWhenIdle: Boolean,
    private val onOpen: ((GenerationBanner) -> Unit)?,
) {
    private val context = host.app
    private var current: GenerationBanner? = null
    private val headline =
        makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSecondaryContainer).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val chapter =
        makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSecondaryContainer).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    private val detail = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSecondaryContainer)
    private val bar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PERCENT
            progressTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.colors.surface)
        }
    private val pauseButton =
        makeButton(context, "Pause", Btn.OUTLINED, R.drawable.wna_pause) {
            host.narrationAction("Could not pause") { pause() }
        }.apply { contentDescription = "Pause generation" }
    private val resumeButton =
        makeButton(context, "Resume", Btn.FILLED, R.drawable.wna_play) {
            host.narrationAction("Could not resume") { resume() }
        }.apply { contentDescription = "Resume generation" }

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
            background = ripple(roundedBg(ThemeManager.colors.secondaryContainer, radius), radius, ThemeManager.colors.onSecondaryContainer)
            setPadding(context.dp(Space.MD), context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
            val texts =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(headline)
                    addView(chapter)
                    addView(
                        bar,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(BAR_HEIGHT_DP)).apply {
                            topMargin = context.dp(Space.XS)
                            bottomMargin = context.dp(Space.XS)
                        },
                    )
                    addView(detail)
                }
            addView(
                texts,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Space.SM) },
            )
            addView(pauseButton)
            addView(resumeButton)
            if (onOpen != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { current?.let(onOpen) }
            }
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(Space.SM)
                }
        }

    fun bind(banner: GenerationBanner) {
        current = banner
        val hidden = hideWhenIdle && banner.state == GenerationBanner.State.IDLE
        view.visibility = if (hidden) View.GONE else View.VISIBLE
        headline.text = banner.headline
        chapter.setTextOrHide(listOfNotNull(banner.chapterTitle, banner.storyTitle).joinToString(" · ").ifEmpty { null })
        detail.setTextOrHide(banner.detail)
        bar.visibility = if (banner.fraction != null || banner.indeterminate) View.VISIBLE else View.GONE
        bar.isIndeterminate = banner.indeterminate
        banner.fraction?.let { bar.progress = (it.coerceIn(0f, 1f) * PERCENT).toInt() }
        val paused = banner.state == GenerationBanner.State.PAUSED
        val active = banner.state != GenerationBanner.State.IDLE && banner.state != GenerationBanner.State.FAILED
        pauseButton.visibility = if (active && !paused) View.VISIBLE else View.GONE
        resumeButton.visibility = if (paused) View.VISIBLE else View.GONE
        view.contentDescription = listOfNotNull(banner.headline, banner.chapterTitle, banner.storyTitle, banner.detail).joinToString(". ")
    }

    /** Keeps the banner current until [scope] ends (the screen's observer). */
    fun observe(
        host: ScreenHost,
        scope: CoroutineScope,
    ) {
        val narration = host.narration
        bind(NarrationScreenPlanning.banner(narration.progress.value, narration.queueState.value, narration.generationRunning.value))
        scope.launch {
            combine(narration.progress, narration.queueState, narration.generationRunning) { progress, queue, running ->
                NarrationScreenPlanning.banner(progress, queue, running)
            }.collect(::bind)
        }
    }

    private fun TextView.setTextOrHide(value: String?) {
        text = value.orEmpty()
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val PERCENT = 100
        const val BAR_HEIGHT_DP = 6
    }
}
