package com.vinicius741.webnovelarchiver.ui

import android.view.View
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.PublicationStatusPlanning
import com.vinicius741.webnovelarchiver.domain.story.PublicationStatusPlanning.DisplayStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

internal fun DisplayStatus.displayName(): String? =
    when (this) {
        DisplayStatus.Archived -> "Archived"
        DisplayStatus.Completed -> "Completed"
        DisplayStatus.Ongoing -> "Ongoing"
        DisplayStatus.Outdated -> "Outdated"
        DisplayStatus.Hiatus -> "Hiatus"
        DisplayStatus.Unknown -> null
    }

internal fun ScreenHost.publicationStatusBadge(story: Story): View? =
    publicationStatusBadge(
        PublicationStatusPlanning.displayStatus(story),
    )

private fun ScreenHost.publicationStatusBadge(status: DisplayStatus): View? {
    val label = status.displayName() ?: return null
    val colors = ThemeManager.colors
    val container =
        when (status) {
            DisplayStatus.Archived,
            DisplayStatus.Completed,
            -> colors.tertiaryContainer
            DisplayStatus.Ongoing -> colors.primaryContainer
            DisplayStatus.Outdated -> colors.errorContainer
            DisplayStatus.Hiatus -> colors.secondaryContainer
            DisplayStatus.Unknown -> colors.surfaceVariant
        }
    val content =
        when (status) {
            DisplayStatus.Archived,
            DisplayStatus.Completed,
            -> colors.onTertiaryContainer
            DisplayStatus.Ongoing -> colors.onPrimaryContainer
            DisplayStatus.Outdated -> colors.onErrorContainer
            DisplayStatus.Hiatus -> colors.onSecondaryContainer
            DisplayStatus.Unknown -> colors.onSurfaceVariant
        }
    return makeBadge(app, label, container, content)
}
