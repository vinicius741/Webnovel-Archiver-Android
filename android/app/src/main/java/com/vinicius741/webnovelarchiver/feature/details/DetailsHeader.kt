package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeProgressSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.scoreRow

/** Centered story header — cover, title, author, source/archived chips, an optional score row, and
 *  a compact "Saved / Chapters" progress summary. Mirrors the RN `StoryHeader`; D5 collapsed the
 *  three duplicate stat pills (Score/Chapters/Saved) — the chapter total is implied by the list —
 *  into one progress summary. The score is now rendered directly in the header via [scoreRow],
 *  matching the library card, instead of only appearing as a tag. */
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
    val publicationStatusBadge = publicationStatusBadge(story.publicationStatus)
    if (provider != null || publicationStatusBadge != null || story.isArchived == true) {
        val badgeRow =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

        fun addBadgeWithGap(view: View) {
            if (badgeRow.childCount > 0) {
                badgeRow.addView(
                    View(app).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(Space.SM), 0)
                    },
                )
            }
            badgeRow.addView(view)
        }
        provider?.let {
            addBadgeWithGap(makeBadge(app, it.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
        }
        publicationStatusBadge?.let {
            addBadgeWithGap(it)
        }
        if (story.isArchived == true) {
            addBadgeWithGap(makeBadge(app, "Archived", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
        }
        col.addView(badgeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // Score (e.g. "4.5" with a star glyph). Rendered in the header so the rating is visible on the
    // details screen too, mirroring the library card — not only on the library list. The star is
    // enlarged (24dp vs the 16dp library card default) since the header has room to breathe and the
    // rating is a focal point on this screen.
    story.score?.takeIf { it.isNotBlank() }?.let { score ->
        col.addView(
            scoreRow(score, iconSizeDp = 24).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.SM)
                    }
            },
        )
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
