package com.vinicius741.webnovelarchiver.feature.ai

import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.feature.details.makeStoryOperationProgress
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState

/** Only the attached AI Controls tree owns these Views. Drafts have a separate activity lifetime. */
internal class AiControlsBinding(
    val storyId: String,
    val root: LinearLayout,
    val busyKind: StoryOperationKind?,
) {
    private val messages = mutableMapOf<StoryOperationKind, TextView>()

    fun addProgress(operation: StoryOperationState) {
        root.addView(makeStoryOperationProgress(root.context, operation, indeterminate = true) { messages[operation.kind] = it })
    }

    fun patch(operation: StoryOperationState): Boolean {
        val label = messages[operation.kind] ?: return false
        label.text = operation.message
        return true
    }
}

/** Rebuild only for changed controls/drafts. Late messages cannot update a detached or different story. */
internal fun ScreenHost.updateAiControlsProgress(
    operation: StoryOperationState,
    structuralChange: Boolean = false,
) {
    val binding = aiControlsScreenState.binding ?: return
    if (binding.storyId != operation.storyId || !frameIsAiControls(operation.storyId) || !binding.root.isAttachedToWindow) return
    val busyKind = storyOperation?.takeIf { it.storyId == operation.storyId }?.kind
    if (structuralChange || binding.busyKind != busyKind || !binding.patch(operation)) {
        showAiControls(operation.storyId)
    }
}
