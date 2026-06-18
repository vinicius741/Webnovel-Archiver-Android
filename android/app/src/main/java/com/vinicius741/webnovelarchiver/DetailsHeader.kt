package com.vinicius741.webnovelarchiver

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeProgressSummary
import com.vinicius741.webnovelarchiver.ui.makeText

/** Centered story header — cover, title, author, source/archived chips, and a compact
 *  "Saved / Chapters" progress summary. Mirrors the RN `StoryHeader`; D5 collapsed the three
 *  duplicate stat pills (Score/Chapters/Saved) — Score already shows in the tags row and the
 *  chapter total is implied by the list — into one progress summary. */
internal fun ScreenHost.buildDetailsHeader(story: Story): LinearLayout {
    val col =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Space.XS), 0, dp(Space.SM))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    val cover = coverImage(story, widthDp = 150, heightDp = 225, tapToOpen = true)
    (cover.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(Space.LG))
    col.addView(cover)
    col.addView(
        makeText(app, story.title, Type.HEADLINE, ThemeManager.colors.onSurface).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        },
    )
    col.addView(
        makeText(app, story.author, Type.TITLE_MEDIUM, ThemeManager.colors.secondary).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(2), 0, dp(Space.MD))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        },
    )
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    if (provider != null || story.isArchived == true) {
        val badgeRow =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        provider?.let {
            badgeRow.addView(makeBadge(app, it.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
        }
        if (story.isArchived == true) {
            if (provider != null) {
                val spacer =
                    View(app).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(Space.SM), 0)
                    }
                badgeRow.addView(spacer)
            }
            badgeRow.addView(makeBadge(app, "Archived", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
        }
        col.addView(badgeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    if (story.totalChapters > 0) {
        col.addView(
            makeProgressSummary(app, story.downloadedChapters, story.totalChapters).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.XS)
                        bottomMargin = dp(Space.XS)
                    }
            },
        )
    }
    return col
}
