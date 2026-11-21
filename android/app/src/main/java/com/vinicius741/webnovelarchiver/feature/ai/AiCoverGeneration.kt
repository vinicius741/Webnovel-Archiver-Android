package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.confirm

// Billable cover-art generation, driven by the Cover tab's prompt box: an empty box runs both
// OpenRouter stages in one job (AI prompt + image); a filled box paints just that prompt; "Draft a
// prompt" runs only the prompt stage and puts the result in the box. These confirm what a run costs;
// the model starts the job on the process-wide AiCoverJobCoordinator, so navigating away never
// cancels an in-flight call. All share the AI_COVER operation slot.

/** Generate cover: an empty [prompt] lets AI write one first (two calls); otherwise paints [prompt]. */
internal fun ScreenHost.generateAiCover(
    render: AiTabRender,
    prompt: String,
) {
    val settings = render.state.settings
    if (prompt.isNotBlank()) {
        val message =
            "Generate the image with ${settings.imageModel}? This calls OpenRouter and uses your API credits. " +
                "Earlier covers remain in Saved covers."
        confirm(message, confirmLabel = "Generate") { render.model.startCover(AiCoverJobKind.IMAGE, prompt) }
        return
    }
    // First-ever run skips the confirmation, as before; repeat runs say what they cost.
    if (render.story.aiCoverPath == null && render.state.coverDraft == null) {
        render.model.startCover(AiCoverJobKind.ONE_STEP)
        return
    }
    val message =
        "Generate a new AI cover with ${settings.imageModel}? This makes two OpenRouter calls " +
            "(image prompt + image) and uses your API credits. " +
            "Automatic selection also uses OpenRouter credits for uncached chapters. Earlier covers remain in Saved covers."
    confirm(message, confirmLabel = "Generate") { render.model.startCover(AiCoverJobKind.ONE_STEP) }
}

/** Write prompt with AI: fills the prompt box without painting; confirms before replacing typed text. */
internal fun ScreenHost.writeAiCoverPrompt(
    render: AiTabRender,
    currentPrompt: String,
) {
    if (currentPrompt.isBlank() && render.story.aiCoverPath == null) {
        render.model.startCover(AiCoverJobKind.PROMPT)
        return
    }
    val model = render.state.settings.descriptionModel
    val message =
        "Write a new image prompt with $model? This calls OpenRouter and uses your API credits. " +
            "Automatic selection also uses OpenRouter credits for uncached chapters." +
            (if (currentPrompt.isNotBlank()) " It replaces the prompt in the box." else "")
    confirm(message, confirmLabel = "Write prompt") { render.model.startCover(AiCoverJobKind.PROMPT) }
}
