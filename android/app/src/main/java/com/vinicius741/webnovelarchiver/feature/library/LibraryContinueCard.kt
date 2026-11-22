package com.vinicius741.webnovelarchiver.feature.library

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
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

/**
 * A quiet way back into the last book when nothing is loaded in the player. It stays hidden while the
 * mini-player is showing, and after an explicit stop it appears the next time the Library opens.
 * Dismissing it lasts across restarts, until the listener plays something again. [bind] shows the
 * card the model found, or hides the slot for null.
 */
internal class LibraryContinueCard(
    private val host: ScreenHost,
    private val onPlay: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val app = host.app
    private var shown: LibraryContinue? = null

    val view: LinearLayout =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = app.dp(SCREEN_SIDE_PADDING_DP)
                    marginEnd = app.dp(SCREEN_SIDE_PADDING_DP)
                    bottomMargin = app.dp(Spacing.SM)
                }
        }

    fun bind(card: LibraryContinue?) {
        if (card == shown) return
        shown = card
        view.removeAllViews()
        if (card == null) {
            view.visibility = View.GONE
            return
        }
        view.addView(buildCard(card.target))
        view.visibility = View.VISIBLE
    }

    private fun buildCard(target: TtsContinueListening.Target): View {
        val colors = ThemeManager.colors
        val card =
            makeCard(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(app.dp(Spacing.MD), app.dp(Spacing.MD), app.dp(Spacing.XS), app.dp(Spacing.MD))
                clipToOutline = true
                foreground =
                    com.vinicius741.webnovelarchiver.ui
                        .selectableRipple(colors.onSurface)
                isClickable = true
                isFocusable = true
                contentDescription = "Continue listening: ${target.story.title}, ${target.chapter.title}"
                setOnClickListener { onPlay() }
            }
        card.addView(
            host.coverImage(target.story, widthDp = COVER_WIDTH_DP, heightDp = COVER_WIDTH_DP * 3 / 2, tapToOpen = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = app.dp(Spacing.MD)
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
                        setPadding(0, app.dp(2), 0, 0)
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
                val size = app.dp(PLAY_DP)
                background = ripple(roundedBg(colors.primary, size / 2f), size / 2f, colors.onPrimary)
                setOnClickListener { onPlay() }
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = app.dp(Spacing.SM) }
            },
        )
        card.addView(app.iconButton(R.drawable.wna_close, "Dismiss continue listening", colors.onSurfaceVariant) { onDismiss() })
        return card
    }
}

private const val COVER_WIDTH_DP = 36
private const val PLAY_DP = 44
