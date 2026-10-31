package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.data.storage.AiCoverResultKind

// Decides what the app-wide AI job chip shows: the running job, a failure, or an unseen result,
// so the user can always get back to the novel where they started a slow generation.

internal enum class AiJobChipStatus { RUNNING, READY, FAILED }

internal data class AiJobChipState(
    val storyId: String,
    val tab: AiControlsTab,
    val status: AiJobChipStatus,
    val headline: String,
    val storyTitle: String,
    /** Key for hiding this exact entry; null while running (a running job cannot be dismissed). */
    val dismissKey: String?,
)

internal data class AiJobChipInput(
    /** Running cover job: story id to progress message. */
    val runningCover: Pair<String, String>? = null,
    /** Running description job: story id to progress message. */
    val runningDescription: Pair<String, String>? = null,
    val unseenCovers: Map<String, AiCoverResultKind> = emptyMap(),
    val unseenDescriptions: Set<String> = emptySet(),
    /** Failures not yet acknowledged, oldest first: dismiss key to (story id, tab, message). */
    val failures: Map<String, AiJobFailure> = emptyMap(),
    val dismissed: Set<String> = emptySet(),
    /** Story whose AI Controls screen is visible; its own page already shows its state. */
    val viewingStoryId: String? = null,
)

internal data class AiJobFailure(
    val storyId: String,
    val tab: AiControlsTab,
    val message: String,
)

internal object AiJobChipPlanning {
    fun failureKey(
        storyId: String,
        tab: AiControlsTab,
    ): String = "failed:${tab.name}:$storyId"

    fun readyKey(
        storyId: String,
        tab: AiControlsTab,
    ): String = "ready:${tab.name}:$storyId"

    /** Running jobs win, then the newest failure, then unseen covers, then unseen descriptions. */
    fun plan(
        input: AiJobChipInput,
        titleOf: (String) -> String?,
    ): AiJobChipState? {
        fun visible(storyId: String) = storyId != input.viewingStoryId && titleOf(storyId) != null

        input.runningCover?.takeIf { visible(it.first) }?.let { (storyId, message) ->
            return AiJobChipState(storyId, AiControlsTab.COVER, AiJobChipStatus.RUNNING, message, titleOf(storyId)!!, null)
        }
        input.runningDescription?.takeIf { visible(it.first) }?.let { (storyId, message) ->
            return AiJobChipState(storyId, AiControlsTab.DESCRIPTION, AiJobChipStatus.RUNNING, message, titleOf(storyId)!!, null)
        }
        input.failures.entries
            .lastOrNull { (key, failure) -> key !in input.dismissed && visible(failure.storyId) }
            ?.let { (key, failure) ->
                return AiJobChipState(
                    failure.storyId,
                    failure.tab,
                    AiJobChipStatus.FAILED,
                    failure.message,
                    titleOf(failure.storyId)!!,
                    key,
                )
            }
        input.unseenCovers.entries
            .firstOrNull { (storyId, _) -> readyKey(storyId, AiControlsTab.COVER) !in input.dismissed && visible(storyId) }
            ?.let { (storyId, kind) ->
                val headline = if (kind == AiCoverResultKind.IMAGE) "New cover ready" else "Cover prompt ready"
                return AiJobChipState(
                    storyId,
                    AiControlsTab.COVER,
                    AiJobChipStatus.READY,
                    headline,
                    titleOf(storyId)!!,
                    readyKey(storyId, AiControlsTab.COVER),
                )
            }
        input.unseenDescriptions
            .firstOrNull { storyId -> readyKey(storyId, AiControlsTab.DESCRIPTION) !in input.dismissed && visible(storyId) }
            ?.let { storyId ->
                return AiJobChipState(
                    storyId,
                    AiControlsTab.DESCRIPTION,
                    AiJobChipStatus.READY,
                    "Description ready",
                    titleOf(storyId)!!,
                    readyKey(storyId, AiControlsTab.DESCRIPTION),
                )
            }
        return null
    }
}
