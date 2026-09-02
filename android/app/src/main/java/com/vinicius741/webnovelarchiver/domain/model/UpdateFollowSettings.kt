package com.vinicius741.webnovelarchiver.domain.model

/** Follow-updates threshold: a novel is followed while its bookmark is this many chapters from the
 *  local chapter-list end. Only the number is persisted; the followed set is always derived. */
data class UpdateFollowSettings(
    val thresholdChapters: Int = 5,
)
