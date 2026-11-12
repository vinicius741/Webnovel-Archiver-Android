package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview

/**
 * The last Updates [Overview] planned and the library it was planned from. Forecasting every novel
 * is the costly part of opening Updates, so the navigation badge plans it off the main thread
 * whenever the library changes and the screen reuses that plan instead of forecasting on the tap.
 *
 * The library is matched by story identity: the repository publishes copy-on-write snapshots, so a
 * changed or restored story is a new object, while a queue-only publish keeps every story object.
 */
internal class UpdatesOverviewCache {
    private class Planned(
        val library: List<Story>,
        val plannedAt: Long,
        val overview: Overview,
    )

    @Volatile
    private var planned: Planned? = null

    /** True when [library] at [now] has no fresh plan (see [isFresh]); safe to call from any thread. */
    fun needsPlan(
        library: List<Story>,
        now: Long,
    ): Boolean = planned?.let { !isFresh(it.library, it.plannedAt, library, now) } ?: true

    /** Plans the overview for [library] at [now] and keeps it; safe to call from any thread. */
    fun plan(
        library: List<Story>,
        now: Long,
    ): Overview = UpdatesPlanning.overview(library, now).also { planned = Planned(library, now, it) }

    /** The kept overview when it is still fresh for [library] at [now], else a new plan. */
    fun overview(
        library: List<Story>,
        now: Long,
    ): Overview {
        val cached = planned
        return if (cached != null && isFresh(cached.library, cached.plannedAt, library, now)) cached.overview else plan(library, now)
    }

    companion object {
        /** Forecasts drift with the clock ("expected in 3 h", may-be-out cut-offs), so plans expire. */
        const val MAX_AGE_MS = 60_000L

        /** A plan is fresh for the same stories (by identity, in order) within [MAX_AGE_MS]. */
        fun isFresh(
            plannedLibrary: List<Story>,
            plannedAt: Long,
            library: List<Story>,
            now: Long,
        ): Boolean = now - plannedAt in 0..MAX_AGE_MS && sameStories(plannedLibrary, library)

        fun sameStories(
            a: List<Story>,
            b: List<Story>,
        ): Boolean = a === b || (a.size == b.size && a.indices.all { a[it] === b[it] })
    }
}
