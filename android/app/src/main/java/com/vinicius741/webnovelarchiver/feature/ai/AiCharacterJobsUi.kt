package com.vinicius741.webnovelarchiver.feature.ai

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText

// The Characters tab's job progress line, its start confirmation, and its busy note.

/** The running character job's progress line; the renderer patches its text as the job reports. */
internal fun addCharacterProgress(
    container: LinearLayout,
    render: AiTabRender,
    job: CharacterJobState,
) {
    container.addView(
        makeText(container.context, job.message, Type.BODY_SMALL, ThemeManager.colors.tertiary).apply {
            setPadding(0, container.context.dp(Space.SM), 0, 0)
            render.characterProgress = this
        },
    )
}

/** Paints a portrait; another one of the same character says what it costs first. */
internal fun ScreenHost.startPortrait(
    render: AiTabRender,
    character: StoryCharacter,
    firstRun: Boolean,
) {
    if (firstRun) {
        render.model.startPortrait(character)
        return
    }
    confirm(
        "Paint another portrait of ${character.name}? This calls OpenRouter and uses your API credits. Earlier portraits are kept.",
        confirmLabel = "Generate",
    ) { render.model.startPortrait(character) }
}

/** Explains a disabled button when the running character job belongs to another novel. */
internal fun addOtherNovelBusyNote(
    container: LinearLayout,
    render: AiTabRender,
    job: CharacterJobState?,
) {
    if (job != null || !render.state.charactersBusy) return
    container.addView(
        makeText(
            container.context,
            "Another novel's character job is running. This one can start when it finishes.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).apply { setPadding(0, container.context.dp(Space.SM), 0, 0) },
    )
}
