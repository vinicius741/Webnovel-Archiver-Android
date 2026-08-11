package com.vinicius741.webnovelarchiver.ui

import android.view.View
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

internal fun SourceAvailability.displayName(): String? =
    when (this) {
        SourceAvailability.available -> null
        SourceAvailability.not_found -> "Source unavailable"
        SourceAvailability.access_restricted -> "Source access blocked"
    }

internal fun ScreenHost.sourceAvailabilityBadge(story: Story): View? {
    if (story.isArchived == true) return null
    val availability = story.sourceSyncState.availability
    val label = availability.displayName() ?: return null
    val colors = ThemeManager.colors
    val content =
        when (availability) {
            SourceAvailability.available -> colors.onSurfaceVariant
            SourceAvailability.not_found -> colors.error
            SourceAvailability.access_restricted -> colors.tertiary
        }
    return makeBadge(app, label, colors.surfaceVariant, content)
}
