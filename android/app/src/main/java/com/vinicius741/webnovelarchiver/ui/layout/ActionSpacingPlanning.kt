package com.vinicius741.webnovelarchiver.ui.layout

/** What sits directly above an action (a button or a row of buttons) in a vertical layout. */
internal sealed interface PrecedingView {
    /** The action is the first visible child, so its container's padding already separates it. */
    data object None : PrecedingView

    /** The caller placed a spacer on purpose; that spacer is the gap. */
    data object Spacer : PrecedingView

    /** Other content, with the empty space it already leaves beneath itself. */
    data class Content(
        val spaceBelowPx: Int,
    ) : PrecedingView
}

/**
 * Decides the top margin that keeps an action clear of the content above it. Lives behind the view
 * DSL's action builders so screens get the gap by default instead of remembering to add one.
 */
internal object ActionSpacingPlanning {
    fun topMarginPx(
        gapPx: Int,
        preceding: PrecedingView,
    ): Int =
        when (preceding) {
            PrecedingView.None, PrecedingView.Spacer -> 0
            is PrecedingView.Content -> (gapPx - preceding.spaceBelowPx).coerceAtLeast(0)
        }
}
