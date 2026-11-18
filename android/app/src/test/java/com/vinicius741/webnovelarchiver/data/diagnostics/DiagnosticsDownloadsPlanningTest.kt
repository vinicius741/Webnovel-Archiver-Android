package com.vinicius741.webnovelarchiver.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsDownloadsPlanningTest {
    @Test
    fun keepsNewestExportsOfEachKindAndIgnoresOtherFiles() {
        val names =
            listOf(
                "webnovel_activity_log_100.json",
                "webnovel_activity_log_400.json",
                "webnovel_activity_log_200.json",
                "webnovel_activity_log_300.json",
                "webnovel_source_access_log_100.json",
                "webnovel_source_access_log_200.json",
                "notes.txt",
            )

        val pruned = DiagnosticsDownloadsPlanning.namesToPrune(names, keepPerKind = 3)

        assertEquals(listOf("webnovel_activity_log_100.json"), pruned)
    }

    @Test
    fun countsRenamedDuplicatesAsTheSameKind() {
        val names = listOf("webnovel_source_access_log_100 (1).json", "webnovel_source_access_log_200.json")

        val pruned = DiagnosticsDownloadsPlanning.namesToPrune(names, keepPerKind = 1)

        assertEquals(listOf("webnovel_source_access_log_100 (1).json"), pruned)
    }
}
