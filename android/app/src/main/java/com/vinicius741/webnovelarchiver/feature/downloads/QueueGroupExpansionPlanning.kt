package com.vinicius741.webnovelarchiver.feature.downloads

/** Keeps a large active queue from materializing every chapter row before the user asks to see it. */
object QueueGroupExpansionPlanning {
    const val MAX_AUTO_EXPANDED_JOBS = 50

    fun shouldExpand(
        userOverride: Boolean?,
        jobCount: Int,
        hasActive: Boolean,
        hasFailed: Boolean,
    ): Boolean =
        userOverride
            ?: ((hasActive || hasFailed) && jobCount <= MAX_AUTO_EXPANDED_JOBS)
}
