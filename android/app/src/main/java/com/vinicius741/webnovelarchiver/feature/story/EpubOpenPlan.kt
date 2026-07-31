package com.vinicius741.webnovelarchiver.feature.story

internal enum class EpubOpenPlan {
    LAUNCH,
    SHOW_READER_REQUIRED,
}

internal fun planEpubOpen(hasEpubReader: Boolean): EpubOpenPlan =
    if (hasEpubReader) {
        EpubOpenPlan.LAUNCH
    } else {
        EpubOpenPlan.SHOW_READER_REQUIRED
    }
