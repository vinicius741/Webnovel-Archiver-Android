package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.ai.rerenderDetailsIfVisible
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import kotlinx.coroutines.launch

/** Source-health notice that explicitly separates remote availability from preserved local data. */
internal fun ScreenHost.buildSourceAvailabilityNotice(story: Story): LinearLayout {
    val colors = ThemeManager.colors
    val providerName = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name ?: "The source"
    val availability = story.sourceSyncState.availability
    val downloadedLabel =
        "${story.downloadedChapters} downloaded chapter${if (story.downloadedChapters == 1) "" else "s"}"
    val message =
        if (availability == SourceAvailability.not_found) {
            "$providerName no longer returns this fiction. Your local copy remains available here with $downloadedLabel."
        } else {
            "Automated access to $providerName is currently blocked. Your local copy remains available here with $downloadedLabel."
        }
    val accent = if (availability == SourceAvailability.not_found) colors.error else colors.tertiary
    return detailsNoticeCard("Your local copy is safe", message, accent)
}

/**
 * Shown while the novel keeps chapters its source no longer lists. They read like any other
 * chapter; the notice explains the row label and offers to drop them.
 */
internal fun ScreenHost.buildRemovedChaptersNotice(story: Story): LinearLayout {
    val removed = story.chapters.filter { it.isRemovedFromSource }
    val providerName = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name ?: "The source"
    val count = removed.size
    val noun = if (count == 1) "chapter" else "chapters"
    val title = "$count $noun removed from source"
    val message =
        "$providerName no longer lists ${if (count == 1) "this chapter" else "these chapters"}. " +
            "${if (count == 1) "It stays" else "They stay"} in your copy, in reading order."
    return detailsNoticeCard(title, message, ThemeManager.colors.tertiary).apply {
        addView(
            makeButton(app, "Discard", Btn.TEXT) {
                confirm(
                    "Discard the $count $noun removed from $providerName? The source can't provide them again.",
                    confirmLabel = "Discard",
                ) {
                    scope.launch {
                        repository.discardRemovedChapters(story.id)
                        rerenderDetailsIfVisible(story.id)
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = dp(Space.SM)
            },
        )
    }
}

private fun ScreenHost.detailsNoticeCard(
    title: String,
    message: String,
    accent: Int,
): LinearLayout {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Space.MD), dp(Space.MD), dp(Space.MD), dp(Space.MD))
        background = strokeBg(colors.elevation1, radius, colors.outlineVariant, dp(1))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.MD)
            }
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    View(app).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        background = roundedBg(accent, dp(2).toFloat())
                    },
                    LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = dp(Space.MD)
                    },
                )
                addView(
                    LinearLayout(app).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeText(app, title, Type.TITLE_MEDIUM, colors.onSurface))
                        addView(
                            makeText(app, message, Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                                setPadding(0, dp(Space.XS), 0, 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )
    }
}
