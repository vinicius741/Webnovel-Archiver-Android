package com.vinicius741.webnovelarchiver.ui

import android.view.View
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.PublicationStatusPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

internal fun PublicationStatus.displayName(): String? =
    when (this) {
        PublicationStatus.completed -> "Completed"
        PublicationStatus.ongoing -> "Ongoing"
        PublicationStatus.outdated -> "Outdated"
        PublicationStatus.hiatus -> "Hiatus"
        PublicationStatus.unknown -> null
    }

internal fun ScreenHost.publicationStatusBadge(story: Story): View? =
    publicationStatusBadge(
        PublicationStatusPlanning.effectiveStatus(story),
    )

internal fun ScreenHost.publicationStatusBadge(status: PublicationStatus): View? {
    val label = status.displayName() ?: return null
    val colors = ThemeManager.colors
    val container =
        when (status) {
            PublicationStatus.completed -> colors.tertiaryContainer
            PublicationStatus.ongoing -> colors.primaryContainer
            PublicationStatus.outdated -> colors.errorContainer
            PublicationStatus.hiatus -> colors.secondaryContainer
            PublicationStatus.unknown -> colors.surfaceVariant
        }
    val content =
        when (status) {
            PublicationStatus.completed -> colors.onTertiaryContainer
            PublicationStatus.ongoing -> colors.onPrimaryContainer
            PublicationStatus.outdated -> colors.onErrorContainer
            PublicationStatus.hiatus -> colors.onSecondaryContainer
            PublicationStatus.unknown -> colors.onSurfaceVariant
        }
    return makeBadge(app, label, container, content)
}
