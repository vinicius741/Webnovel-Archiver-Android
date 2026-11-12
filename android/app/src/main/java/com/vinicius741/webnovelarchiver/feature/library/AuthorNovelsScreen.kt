package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.AuthorPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.selectableRipple

/** Other library novels by the author of [storyId], opened by tapping the author on Details. */
internal fun ScreenHost.showAuthorNovels(storyId: String) {
    val story = repository.story(storyId) ?: return showLibrary()
    rerender = { showAuthorNovels(storyId) }
    val others = AuthorPlanning.otherNovels(story, repository.library())
    val author = story.author.trim().ifEmpty { "Unknown author" }
    val subtitle =
        when (others.size) {
            0 -> "No other novels in your library"
            1 -> "1 other novel in your library"
            else -> "${others.size} other novels in your library"
        }
    screen(route = AppRoute.AuthorNovels(story.id), title = author, subtitle = subtitle, scrollable = true) {
        addView(authorHero(story, author, others.size + 1))
        section("You came from")
        addView(browseCard(story))
        section("Also by $author")
        if (others.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "No other novels",
                    message = "Nothing else by $author is in your library yet.",
                    iconRes = R.drawable.wna_menu_book,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            return@screen
        }
        others.forEach { addView(browseCard(it)) }
    }
}

/** Initial in a copper disc, the author's name, and how many of their novels are in the library. */
private fun ScreenHost.authorHero(
    story: Story,
    author: String,
    novels: Int,
): LinearLayout {
    val colors = ThemeManager.colors
    val source = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name
    val avatarSize = dp(AVATAR_DP)
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.XS), dp(Space.SM), dp(Space.XS), 0)
        addView(
            makeText(app, author.take(1).uppercase(), Type.HEADLINE, colors.onSecondaryContainer).apply {
                gravity = Gravity.CENTER
                background = roundedBg(colors.secondaryContainer, avatarSize / 2f)
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(Space.MD + 2) },
        )
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(app, author, Type.TITLE_MEDIUM, colors.onSurface).apply { textSize = AUTHOR_SP })
                val count = if (novels == 1) "1 novel in your library" else "$novels novels in your library"
                addView(makeText(app, listOfNotNull(count, source).joinToString(" · "), Type.BODY_SMALL, colors.onSurfaceVariant))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}

/** Read-only list card (no multi-select) that opens the novel on tap. */
private fun ScreenHost.browseCard(story: Story): LinearLayout =
    makeCard(app).apply {
        val pad = dp(Space.MD)
        setPadding(pad, pad, pad, pad)
        clipToOutline = true
        addView(
            buildStoryCard(story, selected = null),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        foreground = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { showDetails(story.id) }
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.MD)
            }
    }

private const val AVATAR_DP = 56
private const val AUTHOR_SP = 18f
