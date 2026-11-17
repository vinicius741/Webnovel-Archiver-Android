package com.vinicius741.webnovelarchiver.tts

/**
 * Story-description read-aloud was removed (November 2026). Its sessions were keyed on a reserved
 * chapter id; one saved by an older version is never resumed ([TtsSessionPlanning.isResumeEligible]).
 */
object TtsDescriptionPlanning {
    /** Reserved sentinel chapter id; source ids are numeric path fragments, so no collision. */
    const val DESCRIPTION_CHAPTER_ID = "wna:description"

    fun isDescriptionSession(chapterId: String?): Boolean = chapterId == DESCRIPTION_CHAPTER_ID
}
