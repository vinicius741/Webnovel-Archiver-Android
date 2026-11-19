package com.vinicius741.webnovelarchiver.feature.ai

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.toast

// Starting Characters-tab jobs on the process-wide CharacterJobCoordinator, and their progress line.

internal fun ScreenHost.addCharacterProgress(
    container: LinearLayout,
    storyId: String,
    job: CharacterJobState,
) {
    container.addView(
        makeText(app, job.message, Type.BODY_SMALL, ThemeManager.colors.tertiary).apply {
            setPadding(0, dp(Space.SM), 0, 0)
            tag = storyId
            aiControlsScreenState.characterProgress = this
        },
    )
}

/** Patches the visible progress line in place; false when a re-render is needed instead. */
internal fun ScreenHost.patchCharacterProgress(job: CharacterJobState): Boolean {
    val label = aiControlsScreenState.characterProgress ?: return false
    if (label.tag != job.storyId || !label.isAttachedToWindow) return false
    label.text = job.message
    return true
}

internal fun ScreenHost.startFindCharacters(story: Story) {
    val coordinator = app.appContainer.characterJobCoordinator
    if (!coordinator.startFind(story.id)) {
        toast("Please wait for the current character job to finish")
        return
    }
    AiJobForegroundService.start(app, "Finding characters...")
    showAiControls(story.id)
}

internal fun ScreenHost.startPortrait(
    story: Story,
    character: StoryCharacter,
    firstRun: Boolean,
) {
    fun start() {
        if (!app.appContainer.characterJobCoordinator.startPortrait(story.id, character.id)) {
            toast("Please wait for the current character job to finish")
            return
        }
        AiJobForegroundService.start(app, "Painting a portrait...")
        showAiControls(story.id)
    }
    if (firstRun) {
        start()
        return
    }
    confirm(
        "Paint another portrait of ${character.name}? This calls OpenRouter and uses your API credits. Earlier portraits are kept.",
        confirmLabel = "Generate",
    ) { start() }
}
