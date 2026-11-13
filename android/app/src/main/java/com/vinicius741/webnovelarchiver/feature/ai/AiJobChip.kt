package com.vinicius741.webnovelarchiver.feature.ai

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.feature.player.TTS_MINI_PLAYER_TAG
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.systemBarBottom
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

/*
 * App-wide AI job chip: a small floating pill above every screen that shows a running cover or
 * description generation and, once it finishes, turns into "New cover ready" until the user opens
 * the result. Tapping it opens that novel's AI Controls on the right tab, so a slow generation
 * never has to be found again by hand. Built once per activity, like the TTS mini-player.
 */

private class AiJobChipView(
    root: ViewGroup,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val view =
        LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = root.context.dp(Spacing.XS).toFloat()
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { onOpen() }
        }
    private val spinner =
        ProgressBar(root.context).apply {
            isIndeterminate = true
        }
    private val icon = ImageView(root.context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val headline =
        makeText(root.context, "", Type.TITLE_SMALL, 0).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val storyTitle =
        makeText(root.context, "", Type.BODY_SMALL, 0).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val close =
        root.context.iconButton(R.drawable.wna_close, "Dismiss", ThemeManager.colors.onSurfaceVariant, onClick = onDismiss)

    init {
        val context = root.context
        val leadSize = context.dp(20)
        view.setPadding(context.dp(Spacing.MD), context.dp(Spacing.SM), context.dp(Spacing.XS), context.dp(Spacing.SM))
        view.addView(spinner, LinearLayout.LayoutParams(leadSize, leadSize).apply { marginEnd = context.dp(Spacing.SM + 4) })
        view.addView(icon, LinearLayout.LayoutParams(leadSize, leadSize).apply { marginEnd = context.dp(Spacing.SM + 4) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(headline)
        texts.addView(storyTitle)
        view.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Spacing.SM) },
        )
        view.addView(close)
    }

    fun bind(state: AiJobChipState) {
        val colors = ThemeManager.colors
        val context = view.context
        // v3 floating chrome, matching the mini player: a raised obsidian card with a hairline
        // border, onSurface text, and the status carried by the accent (copper = generating,
        // sage = ready, red = failed) instead of a solid tinted fill.
        val accent =
            when (state.status) {
                AiJobChipStatus.RUNNING -> colors.secondary
                AiJobChipStatus.READY -> colors.tertiary
                AiJobChipStatus.FAILED -> colors.error
            }
        val failed = state.status == AiJobChipStatus.FAILED
        val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
        val border = (if (failed) colors.error else colors.primary) and BORDER_ALPHA_MASK
        view.background =
            ripple(strokeBg(CHIP_SURFACE, radius, border, context.dp(1).coerceAtLeast(1)), radius, colors.onSurface)
        headline.setTextColor(colors.onSurface)
        storyTitle.setTextColor(colors.onSurfaceVariant)
        headline.text = state.headline
        storyTitle.text = state.storyTitle
        spinner.indeterminateTintList = ColorStateList.valueOf(accent)
        spinner.visibility = if (state.status == AiJobChipStatus.RUNNING) View.VISIBLE else View.GONE
        icon.visibility = if (state.status == AiJobChipStatus.RUNNING) View.GONE else View.VISIBLE
        icon.setImageDrawable(context.tintedIcon(if (failed) R.drawable.wna_close else R.drawable.wna_auto_awesome, accent))
        close.visibility = if (state.dismissKey != null) View.VISIBLE else View.GONE
        close.setImageDrawable(context.tintedIcon(R.drawable.wna_close, colors.onSurfaceVariant))
        view.contentDescription = "${state.headline}, ${state.storyTitle}. Open AI Controls"
    }
}

internal fun ScreenHost.attachAiJobChip(root: ViewGroup) {
    var current: AiJobChipState? = null
    lateinit var chip: AiJobChipView

    fun reposition() {
        val miniPlayer = root.findViewWithTag<View>(TTS_MINI_PLAYER_TAG)?.takeIf { it.visibility == View.VISIBLE }
        val lift = miniPlayer?.let { it.height + dp(Spacing.SM) } ?: 0
        val lp = chip.view.layoutParams as FrameLayout.LayoutParams
        val bottom = dp(Spacing.LG) + lift
        // Leave the bottom-end corner to screen FABs (Library's add button).
        val maxWidth = (root.width - dp(FAB_CLEARANCE_DP)).coerceAtLeast(dp(MIN_CHIP_WIDTH_DP))
        if (lp.bottomMargin != bottom || lp.width != minOf(maxWidth, dp(MAX_CHIP_WIDTH_DP))) {
            lp.bottomMargin = bottom
            lp.width = minOf(maxWidth, dp(MAX_CHIP_WIDTH_DP))
            chip.view.layoutParams = lp
        }
    }

    fun refresh() {
        val state = aiControlsScreenState
        val coverJob =
            app.appContainer.aiCoverJobCoordinator.jobs.value.values
                .firstOrNull()
        val descriptionJob = aiOperation?.takeIf { it.kind == StoryOperationKind.AI_DESCRIPTION }
        val viewing = (navigator.current as? AppRoute.AiControls)?.storyId
        val next =
            AiJobChipPlanning.plan(
                AiJobChipInput(
                    runningCover = coverJob?.let { it.storyId to it.message },
                    runningDescription = descriptionJob?.let { it.storyId to it.message },
                    unseenCovers = repository.aiCoverUnseen.value,
                    unseenDescriptions = state.unseenDescriptions,
                    failures = state.jobFailures,
                    dismissed = state.dismissedChips,
                    viewingStoryId = viewing,
                ),
            ) { storyId -> repository.story(storyId)?.title }
        current = next
        if (next == null) {
            chip.view.visibility = View.GONE
            return
        }
        chip.bind(next)
        if (chip.view.visibility != View.VISIBLE) {
            chip.view.visibility = View.VISIBLE
            chip.view.alpha = 0f
            chip.view
                .animate()
                .alpha(1f)
                .setDuration(150L)
                .start()
        }
        reposition()
    }

    chip =
        AiJobChipView(
            root,
            onOpen = { current?.let { showAiControls(it.storyId, it.tab) } },
            onDismiss = {
                current?.dismissKey?.let { aiControlsScreenState.dismissedChips += it }
                refresh()
            },
        )
    root.addView(
        chip.view,
        FrameLayout.LayoutParams(dp(MAX_CHIP_WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(Spacing.MD)
        },
    )
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (chip.view.visibility == View.VISIBLE) root.post { reposition() } }

    aiControlsScreenState.refreshJobChip = { refresh() }
    val previousScreenBuilt = onScreenBuilt
    onScreenBuilt = {
        previousScreenBuilt?.invoke()
        refresh()
    }
    scope.launch {
        app.appContainer.aiCoverJobCoordinator.jobs
            .collect { refresh() }
    }
    scope.launch {
        var previous = repository.aiCoverUnseen.value.keys
        repository.aiCoverUnseen.collect { unseen ->
            // Library covers carry a badge for unseen results; rebuild it when one arrives on screen.
            if (navigator.current == AppRoute.Library && !previous.containsAll(unseen.keys)) rerender?.invoke()
            previous = unseen.keys
            refresh()
        }
    }
}

private const val MAX_CHIP_WIDTH_DP = 360
private const val MIN_CHIP_WIDTH_DP = 200
private const val FAB_CLEARANCE_DP = 104

// Same raised surface and gold hairline as the v3 mini player (TtsMiniPlayerBar).
private const val CHIP_SURFACE = 0xFF2A251C.toInt()
private const val BORDER_ALPHA_MASK = 0x29FFFFFF
