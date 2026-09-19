package com.vinicius741.webnovelarchiver.perf

/**
 * Shared vocabulary for debug performance sessions: intent extras the host runner passes,
 * event names the recorder emits, export file names, and buffer caps. Pure constants only so
 * the contract is unit-testable without Android.
 *
 * A session is started only when [EXTRA_SESSION] is truthy in the launch intent (debug variants
 * only — every call site is behind BuildConfig.DEBUG, so release never reads or writes these
 * files). One session spans one activity instance; the export file is overwritten at session
 * start, so a stale file from a previous process is detectable by comparing the embedded
 * runId/scenario/iteration with what the host launched.
 */
object PerfSessionContract {
    const val EXTRA_SESSION = "perf_session"
    const val EXTRA_RUN_ID = "perf_run_id"
    const val EXTRA_SCENARIO = "perf_scenario"
    const val EXTRA_ITERATION = "perf_iteration"

    /** Event name (optionally `name:routeKey`) whose first occurrence completes the scenario. */
    const val EXTRA_COMPLETE_ON = "perf_complete_on"

    /** Session deadline in millis; the recorder emits [EVENT_DEADLINE] if completion misses it. */
    const val EXTRA_MAX_MS = "perf_max_ms"

    const val SESSION_DIR = "perf"
    const val SESSION_FILE = "session.json"
    const val FRAMES_FILE = "frames.jsonl"

    const val EXPORT_FORMAT = "webnovel-perf-session"
    const val EXPORT_VERSION = 1

    const val EVENT_STARTUP_PASS = "startup_pass"
    const val EVENT_UI_READY = "ui_ready"
    const val EVENT_SCREEN_BUILT = "screen_built"
    const val EVENT_READER_PREPARING = "reader_preparing"
    const val EVENT_READER_PREPARE_DONE = "reader_prepare_done"
    const val EVENT_READER_PRESENTED = "reader_content_presented"
    const val EVENT_READER_PAINTED = "reader_content_painted"
    const val EVENT_INTERACTION = "interaction"
    const val EVENT_SCENARIO_COMPLETE = "scenario_complete"
    const val EVENT_DEADLINE = "scenario_deadline"
    const val EVENT_SESSION_START = "session_start"
    const val EVENT_SESSION_END = "session_end"

    const val DETAIL_ROUTE = "route"

    // Hydration can finish before the activity reads the launch intent; these events are held in a
    // small pre-session buffer and merged when a session starts.
    const val PRE_SESSION_BUFFER_CAP = 16
    val PRE_SESSION_EVENTS: Set<String> = setOf(EVENT_STARTUP_PASS)

    // Bounded buffers: when full, new records are dropped and counted in the exported overhead
    // block, never growing without limit or blocking the caller.
    const val MAX_EVENTS = 2000
    const val MAX_MEMORY_SAMPLES = 400
    const val MAX_FRAME_SAMPLES = 30000

    // Flush at most this often per event burst; completion/destroy always flush immediately.
    const val FLUSH_DEBOUNCE_MILLIS = 400L

    // Truncation for exported strings — keeps chapter/story text out of exports.
    const val MAX_DETAIL_LENGTH = 80

    /** Truthy parse mirroring DevLibraryReportPlanning.requested: "1"/"true" trimmed, case-insensitive. */
    fun requested(value: String?): Boolean = !value.isNullOrBlank() && value.trim().lowercase() in TRUTHY

    private val TRUTHY = setOf("1", "true")

    /** Splits a `perf_complete_on` value into the event name and optional route qualifier. */
    fun parseCompleteOn(value: String?): PerfCompleteOnRule {
        val trimmed = value?.trim().orEmpty()
        val parts =
            if (trimmed.isEmpty()) {
                listOf(EVENT_SCREEN_BUILT)
            } else {
                trimmed.split(':', limit = 2)
            }
        val eventName = parts[0].trim().ifEmpty { EVENT_SCREEN_BUILT }
        val route = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return PerfCompleteOnRule(eventName, route)
    }
}

/** Terminal condition for a scenario: the first event named [event] whose route detail (when
 *  [route] is set) matches completes the session. Parsed once at launch, matched per event. */
data class PerfCompleteOnRule(
    val event: String,
    val route: String?,
) {
    fun matches(
        eventName: String,
        routeDetail: String?,
    ): Boolean = eventName == event && (route == null || routeDetail == route)
}
