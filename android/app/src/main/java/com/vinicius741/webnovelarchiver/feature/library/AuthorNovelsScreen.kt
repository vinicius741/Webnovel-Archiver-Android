package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.AuthorPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple

/** Other library novels by the author of [storyId], opened by tapping the author on Details. */
internal fun ScreenHost.showAuthorNovels(storyId: String) {
    val story = repository.story(storyId) ?: return showLibrary()
    rerender = { showAuthorNovels(storyId) }
    val others = AuthorPlanning.otherNovels(story, repository.library())
    val subtitle =
        when (others.size) {
            0 -> "No other novels in your library"
            1 -> "1 other novel in your library"
            else -> "${others.size} other novels in your library"
        }
    screen(route = AppRoute.AuthorNovels(story.id), title = story.author.trim(), subtitle = subtitle, scrollable = true) {
        if (others.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "No other novels",
                    message = "Novels you add by this author will show up here.",
                    iconRes = R.drawable.wna_menu_book,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            return@screen
        }
        others.forEach { other ->
            val card = makeCard(context)
            bindBrowseStoryCard(card, other)
            addView(
                card,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.MD + Space.XS)
                },
            )
        }
    }
}

/** Read-only story card (no multi-select) that opens the novel's details on tap. */
private fun ScreenHost.bindBrowseStoryCard(
    card: LinearLayout,
    story: Story,
) {
    card.removeAllViews()
    val content = buildStoryCard(story, Gravity.CENTER_VERTICAL, selected = null)
    content.background = selectableRipple(ThemeManager.colors.onSurface)
    content.isClickable = true
    content.setOnClickListener { showDetails(story.id) }
    card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (story.totalChapters > 0) card.addView(makeLibraryProgressSummary(story, topMarginDp = Space.MD))
}
