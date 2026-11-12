package com.vinicius741.webnovelarchiver.feature.narration

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// The generation banner on the narration screens (v3 copper card). It always says what is being
// generated, in which story, how far along and how fast, whichever screen the user came from, and
// updates in place on every sentence instead of rebuilding the screen. While nothing is queued it
// turns into a calm "Nothing is generating" card (or hides, on the story screen).

internal class NarrationLiveStatus(
    host: ScreenHost,
    /** Hide the banner while nothing is queued (the story screen) instead of saying so. */
    private val hideWhenIdle: Boolean,
    private val onOpen: ((GenerationBanner) -> Unit)?,
) {
    private val context = host.app
    private val colors = ThemeManager.colors
    private var current: GenerationBanner? = null
    private val stateIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val spinner =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(colors.secondary)
        }
    private val headline =
        makeText(context, "", Type.TITLE_SMALL, colors.onSurface).apply {
            textSize = HEADLINE_SP
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    private val chapter =
        makeText(context, "", Type.BODY_MEDIUM, colors.onSurface).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    private val detail = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val bar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PERCENT
            progressTintList = ColorStateList.valueOf(colors.secondary)
            progressBackgroundTintList = ColorStateList.valueOf(colors.elevation4)
            indeterminateTintList = ColorStateList.valueOf(colors.secondary)
        }
    private val pauseButton =
        makeButton(context, "Pause", Btn.SURFACE, R.drawable.wna_pause) {
            host.narrationAction("Could not pause") { pause() }
        }.apply { contentDescription = "Pause generation" }
    private val resumeButton =
        makeButton(context, "Resume", Btn.FILLED, R.drawable.wna_play) {
            host.narrationAction("Could not resume") { resume() }
        }.apply { contentDescription = "Resume generation" }

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(Space.LG)
            setPadding(pad, context.dp(Space.MD), context.dp(Space.MD), pad)
            val top =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icons =
                        FrameLayout(context).apply {
                            addView(stateIcon, FrameLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP), Gravity.CENTER))
                            addView(spinner, FrameLayout.LayoutParams(context.dp(SPINNER_DP), context.dp(SPINNER_DP), Gravity.CENTER))
                        }
                    addView(
                        icons,
                        LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply {
                            marginEnd =
                                context.dp(Space.MD)
                        },
                    )
                    addView(
                        headline,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd =
                                context.dp(Space.SM)
                        },
                    )
                    addView(pauseButton)
                    addView(resumeButton)
                }
            addView(top)
            addView(
                chapter,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        context.dp(Space.XS)
                },
            )
            addView(
                bar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(BAR_HEIGHT_DP)).apply {
                    topMargin = context.dp(Space.SM + 2)
                },
            )
            addView(
                detail,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        context.dp(Space.SM)
                },
            )
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
        val idle = banner.state == GenerationBanner.State.IDLE
        view.visibility = if (hideWhenIdle && idle) View.GONE else View.VISIBLE
        view.background = if (idle) quietBackground() else copperBackground()
        headline.text = banner.headline
        chapter.setTextOrHide(chapterLine(banner))
        detail.setTextOrHide(if (idle) "Queue chapters from a story's Audio tab." else banner.detail)
        bar.visibility = if (banner.fraction != null || banner.indeterminate) View.VISIBLE else View.GONE
        bar.isIndeterminate = banner.indeterminate
        banner.fraction?.let { bar.progress = (it.coerceIn(0f, 1f) * PERCENT).toInt() }
        bindIcon(banner.state)
        val paused = banner.state == GenerationBanner.State.PAUSED
        val active = !idle && banner.state != GenerationBanner.State.FAILED
        pauseButton.visibility = if (active && !paused) View.VISIBLE else View.GONE
        resumeButton.visibility = if (paused) View.VISIBLE else View.GONE
        view.contentDescription = listOfNotNull(banner.headline, banner.chapterTitle, banner.storyTitle, banner.detail).joinToString(". ")
    }

    private fun bindIcon(state: GenerationBanner.State) {
        val running = state == GenerationBanner.State.GENERATING || state == GenerationBanner.State.STARTING
        spinner.visibility = if (running) View.VISIBLE else View.GONE
        stateIcon.visibility = if (running) View.GONE else View.VISIBLE
        val (icon, tint) =
            when (state) {
                GenerationBanner.State.PAUSED -> R.drawable.wna_pause to colors.secondary
                GenerationBanner.State.FAILED -> R.drawable.wna_error to colors.error
                GenerationBanner.State.WAITING -> R.drawable.wna_schedule to colors.secondary
                GenerationBanner.State.IDLE -> R.drawable.wna_check_circle to colors.tertiary
                else -> R.drawable.wna_graphic_eq to colors.secondary
            }
        stateIcon.setImageDrawable(context.tintedIcon(icon, tint))
    }

    private fun chapterLine(banner: GenerationBanner): CharSequence? {
        val chapterTitle = banner.chapterTitle ?: return banner.storyTitle
        val story = banner.storyTitle ?: return chapterTitle
        val text = android.text.SpannableStringBuilder(chapterTitle).append(" · ")
        val start = text.length
        text.append(story)
        text.setSpan(
            android.text.style.ForegroundColorSpan(colors.onSurfaceVariant),
            start,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return text
    }

    private fun copperBackground() =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(COPPER_START, COPPER_END)).apply {
            cornerRadius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
            setStroke(context.dp(1).coerceAtLeast(1), ThemeManager.colors.secondary and BORDER_ALPHA_MASK)
        }

    private fun quietBackground() =
        strokeBg(colors.elevation1, context.dp(ThemeManager.shapes.cardRadius).toFloat(), colors.line, context.dp(1).coerceAtLeast(1))

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

    private fun TextView.setTextOrHide(value: CharSequence?) {
        text = value ?: ""
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val PERCENT = 100
        const val BAR_HEIGHT_DP = 6
        const val ICON_DP = 24
        const val SPINNER_DP = 20
        const val HEADLINE_SP = 15f
        const val COPPER_START = 0xFF3A261B.toInt()
        const val COPPER_END = 0xFF221B16.toInt()
        const val BORDER_ALPHA_MASK = 0x29FFFFFF
    }
}
