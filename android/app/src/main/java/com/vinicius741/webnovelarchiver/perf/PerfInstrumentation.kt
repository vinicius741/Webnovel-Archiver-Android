package com.vinicius741.webnovelarchiver.perf

/**
 * Event-recording surface for instrumentation call sites. Every function is inert unless a
 * session is active, and every call site is behind BuildConfig.DEBUG so release is unaffected.
 * Hot paths (screen builds, reader milestones) stay allocation-light: the only per-call
 * allocation is the detail map, which is small and short-lived.
 */
object PerfInstrumentation {
    /** Phases come from AppContainer's R30 startup-pass timing; emitted even before session start. */
    fun recordStartupPass(
        stories: Int,
        changed: Int,
        loadMillis: Long,
        migrateMillis: Long,
        hydrateMillis: Long,
        totalMillis: Long,
    ) {
        PerfRecorder.offerEvent(
            PerfSessionContract.EVENT_STARTUP_PASS,
            detail =
                mapOf(
                    "stories" to stories.toString(),
                    "changed" to changed.toString(),
                    "loadMs" to loadMillis.toString(),
                    "migrateMs" to migrateMillis.toString(),
                    "hydrateMs" to hydrateMillis.toString(),
                ),
            durationMillis = totalMillis,
        )
    }

    fun recordUiReady() {
        PerfRecorder.offerEvent(PerfSessionContract.EVENT_UI_READY)
    }

    /**
     * The screen scaffold finished building (main-thread construction incl. the body block).
     * Reader titles are excluded from the export so chapter text never lands in perf artifacts.
     */
    fun recordScreenBuilt(
        route: String,
        title: String,
        subtitle: String?,
        buildDurationNanos: Long,
    ) {
        PerfRecorder.setScreenTag(route)
        val exportedTitle = if (route == READER_ROUTE) "" else title
        val detail =
            buildMap(3) {
                put(PerfSessionContract.DETAIL_ROUTE, route)
                if (exportedTitle.isNotEmpty()) put("title", exportedTitle)
                if (!subtitle.isNullOrBlank()) put("subtitle", subtitle)
            }
        PerfRecorder.offerEvent(
            PerfSessionContract.EVENT_SCREEN_BUILT,
            durationMillis = buildDurationNanos / 1_000_000,
            detail = detail,
        )
        PerfRecorder.submitMemorySnapshot("screen:$route")
    }

    fun recordReaderPreparing(
        storyId: String,
        chapterId: String,
    ) {
        PerfRecorder.offerEvent(
            PerfSessionContract.EVENT_READER_PREPARING,
            detail = mapOf("storyId" to storyId, "chapterId" to chapterId),
        )
    }

    /** Loading completion: the document is resolved/sanitized/rendered to HTML, no WebView yet. */
    fun recordReaderPrepareDone(
        outcome: String,
        durationMillis: Long,
    ) {
        PerfRecorder.offerEvent(
            PerfSessionContract.EVENT_READER_PREPARE_DONE,
            durationMillis = durationMillis,
            detail = mapOf("outcome" to outcome),
        )
    }

    /** Content presentation: the reader WebView has been built and loadDataWithBaseURL ran. */
    fun recordReaderPresented() {
        PerfRecorder.offerEvent(PerfSessionContract.EVENT_READER_PRESENTED)
    }

    /** The WebView finished loading the chapter document (onPageFinished). */
    fun recordReaderPainted() {
        PerfRecorder.offerEvent(PerfSessionContract.EVENT_READER_PAINTED)
    }

    const val READER_ROUTE = "reader"
}
