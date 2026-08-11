package com.vinicius741.webnovelarchiver.feature.updates

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.text

internal fun ScreenHost.buildUpdateSyncErrors(
    errors: Map<String, String>,
    stories: List<Story>,
): LinearLayout? {
    if (errors.isEmpty()) return null
    return LinearLayout(app).card {
        text("${errors.size} sync error${plural(errors.size)}", Type.TITLE_MEDIUM, ThemeManager.colors.error)
        errors.forEach { (storyId, message) ->
            val title = stories.firstOrNull { it.id == storyId }?.title ?: storyId
            text("$title: $message", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        }
    }
}
