package com.vinicius741.webnovelarchiver.ui.layout

/**
 * Pure geometry for the chapter-coverage bar ([com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar]).
 *
 * Kept Android-free on purpose so the seam-free run coalescing and the bookmark slot resolution
 * can be exhaustively unit-tested without a device. The view only maps the returned slot indices
 * to pixels.
 */
object ChapterCoveragePlanning {
    /**
     * Coalesces contiguous `true` flags into `start..end` slot ranges (both inclusive).
     *
     * The bar draws ONE rounded rect per run instead of one rect per chapter: adjacent
     * anti-aliased slot rects share edges at fractional x positions, and two partially-covering
     * edges of the same colour never composite to full opacity — which left a track-coloured
     * hairline at every chapter boundary.
     */
    fun downloadedRuns(downloaded: BooleanArray): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var index = 0
        while (index < downloaded.size) {
            if (!downloaded[index]) {
                index++
                continue
            }
            var end = index
            while (end + 1 < downloaded.size && downloaded[end + 1]) end++
            runs += index..end
            index = end + 1
        }
        return runs
    }

    /**
     * Coalesces contiguous slots of the same non-zero state into `(start..end, state)` runs, the
     * multi-state form of [downloadedRuns]; state 0 is the empty track and is skipped.
     */
    fun stateRuns(states: ByteArray): List<Pair<IntRange, Int>> {
        val runs = mutableListOf<Pair<IntRange, Int>>()
        var index = 0
        while (index < states.size) {
            val state = states[index].toInt()
            var end = index
            while (end + 1 < states.size && states[end + 1].toInt() == state) end++
            if (state != 0) runs += (index..end) to state
            index = end + 1
        }
        return runs
    }

    /**
     * Resolves a 0..1 bookmark fraction to the slot it lands in, clamped into the valid range so
     * the marker always sits inside a real chapter's slot (a bookmark on chapter 1 centres in the
     * leftmost slot instead of clipping against the bar's edge). `null` when there is no bookmark
     * or no chapters.
     */
    fun bookmarkSlot(
        fraction: Float?,
        total: Int,
    ): Int? {
        if (fraction == null || total <= 0) return null
        return (fraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
    }
}
