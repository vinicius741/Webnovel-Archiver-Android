package com.vinicius741.webnovelarchiver.feature.library

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.runUiOperation
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEyebrow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

/**
 * A quiet way back into the last book when nothing is loaded in the player. It stays hidden while the
 * mini-player is showing, and after an explicit stop it appears the next time the Library opens.
 * Dismissing it lasts across restarts, until the listener plays something again.
 */
internal fun ScreenHost.continueListeningCard(): View {
    val slot =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(SCREEN_SIDE_PADDING_DP)
                    marginEnd = dp(SCREEN_SIDE_PADDING_DP)
                    bottomMargin = dp(Spacing.SM)
                }
        }
    if (ttsEngine.playbackState.value.snapshot != null) return slot
    scope.launch {
        val positions = runCatching { repository.getTtsStoryPositions() }.getOrDefault(emptyList())
        val dismissedThrough = repository.getDisplayPreferences().continueListeningDismissedThrough
        val target = TtsContinueListening.target(positions, dismissedThrough) { repository.story(it) } ?: return@launch
        if (ttsEngine.playbackState.value.snapshot != null) return@launch
        val colors = ThemeManager.colors
        val play = {
            playIfGenerated(target.story.id, target.chapter.id) {
                slot.visibility = View.GONE
                TtsForegroundService.start(app, target.story.id, target.chapter.id)
            }
        }
        val card =
            makeCard(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Spacing.MD), dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD))
                clipToOutline = true
                foreground =
                    com.vinicius741.webnovelarchiver.ui
                        .selectableRipple(colors.onSurface)
                isClickable = true
                isFocusable = true
                contentDescription = "Continue listening: ${target.story.title}, ${target.chapter.title}"
                setOnClickListener { play() }
            }
        card.addView(
            coverImage(target.story, widthDp = COVER_WIDTH_DP, heightDp = COVER_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(Spacing.MD)
            },
        )
        card.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeEyebrow(app, "Continue listening"))
                addView(
                    makeText(app, target.story.title, Type.TITLE_SMALL, colors.onSurface).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    },
                )
                addView(
                    makeText(app, target.chapter.title, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(
            ImageView(app).apply {
                contentDescription = "Play"
                setImageDrawable(app.tintedIcon(R.drawable.wna_play, colors.onPrimary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = dp(PLAY_DP)
                background = ripple(roundedBg(colors.primary, size / 2f), size / 2f, colors.onPrimary)
                setOnClickListener { play() }
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(Spacing.SM) }
            },
        )
        card.addView(
            app.iconButton(R.drawable.wna_close, "Dismiss continue listening", colors.onSurfaceVariant) {
                slot.visibility = View.GONE
                persistContinueListeningDismissal(positions.maxOf { it.updatedAt })
            },
        )
        slot.addView(card)
        slot.visibility = View.VISIBLE
    }
    return slot
}

/** Transactional read-modify-write; a failed save is reported instead of silently dropped. */
private fun ScreenHost.persistContinueListeningDismissal(dismissedThrough: Long) {
    runUiOperation("dismiss continue listening") {
        repository.updateDisplayPreferences { it.copy(continueListeningDismissedThrough = dismissedThrough) }
    }
}

private const val COVER_WIDTH_DP = 36
private const val PLAY_DP = 44
