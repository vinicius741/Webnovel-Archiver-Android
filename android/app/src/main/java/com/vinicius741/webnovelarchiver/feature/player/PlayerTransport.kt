package com.vinicius741.webnovelarchiver.feature.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// Now Playing's middle: the "Now reading" card, the big play button and the transport row.

internal fun ScreenHost.nowReadingCard(
    caption: TextView,
    onClick: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val radius = dp(CAPTION_RADIUS_DP).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(CAPTION_MIN_HEIGHT_DP)
        setPadding(dp(Spacing.MD + 2), dp(Spacing.MD), dp(Spacing.MD + 2), dp(Spacing.MD))
        background =
            ripple(
                strokeBg(CAPTION_FILL, radius, CAPTION_BORDER, dp(1).coerceAtLeast(1)),
                radius,
                colors.onSurface,
            )
        isClickable = true
        isFocusable = true
        contentDescription = "Now reading. Open in the reader"
        setOnClickListener { onClick() }
        val eyebrow =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_subtitles, colors.primary)) },
                    LinearLayout.LayoutParams(dp(EYEBROW_ICON_DP), dp(EYEBROW_ICON_DP)).apply { marginEnd = dp(6) },
                )
                addView(
                    makeText(app, "Now reading", Type.LABEL_MEDIUM, colors.primary).apply {
                        isAllCaps = true
                        letterSpacing = EYEBROW_SPACING
                    },
                )
            }
        addView(eyebrow)
        addView(
            caption,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.XS)
            },
        )
        layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Spacing.LG)
                bottomMargin = dp(Spacing.SM)
            }
    }
}

internal fun ScreenHost.playButton(): ImageView {
    val colors = ThemeManager.colors
    val size = dp(PLAY_SIZE_DP)
    val radius = dp(PLAY_RADIUS_DP).toFloat()
    return ImageView(app).apply {
        contentDescription = "Play"
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LinearLayout.LayoutParams(size, size)
        background =
            ripple(
                roundedBg(colors.primary, radius),
                radius,
                colors.onPrimary,
            )
        setOnClickListener { TtsForegroundService.command(app, TtsForegroundService.ACTION_PLAY_PAUSE) }
    }
}

internal fun ScreenHost.transportRow(playPause: ImageView): LinearLayout {
    val colors = ThemeManager.colors

    fun transport(
        desc: String,
        icon: Int,
        action: String,
        badge: String? = null,
    ): View {
        val size = dp(TRANSPORT_SIZE_DP)
        val button = app.iconButton(icon, desc, colors.onSurface) { TtsForegroundService.command(app, action) }
        if (badge != null) {
            // Upscale the ring so the seconds label sits inside it instead of over the stroke.
            button.scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = (size - dp(BADGE_ICON_DP)) / 2
            button.setPadding(inset, inset, inset, inset)
        }
        return FrameLayout(app).apply {
            addView(button, FrameLayout.LayoutParams(size, size))
            badge?.let {
                addView(
                    makeText(app, it, Type.LABEL_SMALL, colors.onSurface).apply {
                        textSize = BADGE_SP
                        includeFontPadding = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout
                        .LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        ).apply {
                            // Ring centre sits below the icon centre (y=13.5 of 24).
                            topMargin = dp(BADGE_OFFSET_DP)
                        },
                )
            }
        }
    }

    fun gap() = View(app).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(Spacing.SM + 2), 0, dp(Spacing.MD + 2))
        addView(transport("Previous chapter", R.drawable.wna_skip_prev, TtsForegroundService.ACTION_PREVIOUS_CHAPTER))
        addView(gap())
        addView(transport("Back 15 seconds", R.drawable.wna_seek_back_ring, TtsForegroundService.ACTION_SKIP_BACK, "15"))
        addView(gap())
        addView(playPause)
        addView(gap())
        addView(transport("Forward 30 seconds", R.drawable.wna_seek_forward_ring, TtsForegroundService.ACTION_SKIP_FORWARD, "30"))
        addView(gap())
        addView(transport("Next chapter", R.drawable.wna_skip_next, TtsForegroundService.ACTION_NEXT_CHAPTER))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}

private const val PLAY_SIZE_DP = 76
private const val PLAY_RADIUS_DP = 26
private const val TRANSPORT_SIZE_DP = 56
private const val BADGE_SP = 10f
private const val BADGE_ICON_DP = 32
private const val BADGE_OFFSET_DP = 4
private const val CAPTION_RADIUS_DP = 16
private const val CAPTION_MIN_HEIGHT_DP = 78
private const val EYEBROW_ICON_DP = 14
private const val EYEBROW_SPACING = 0.08f
private const val CAPTION_FILL = 0x0FFFFFFF
private const val CAPTION_BORDER = 0x12FFFFFF
