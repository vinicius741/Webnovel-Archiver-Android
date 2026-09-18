package com.vinicius741.webnovelarchiver.perf

import android.app.Activity
import android.content.Intent
import android.os.SystemClock

/**
 * Debug-only performance session facade. A session exists only between [maybeStartFromIntent]
 * (launch extra `perf_session`) and activity destroy; every hook call is behind BuildConfig.DEBUG
 * at the call site, so release builds never touch this path and behavior is unchanged.
 *
 * Events offered before the session starts are buffered (bounded) for the small set of
 * [PerfSessionContract.PRE_SESSION_EVENTS] — repository hydration can finish before the
 * activity reads its launch intent.
 */
object PerfRecorder {
    private val lock = Any()
    private var runner: PerfSessionRunner? = null
    private val pendingPreSession = ArrayDeque<PerfRecordedEvent>()

    fun isActive(): Boolean = synchronized(lock) { runner != null }

    fun maybeStartFromIntent(
        activity: Activity,
        intent: Intent,
    ): Boolean {
        if (!PerfSessionContract.requested(intent.getStringExtra(PerfSessionContract.EXTRA_SESSION))) return false
        synchronized(lock) {
            if (runner != null) return false
            val completeOn =
                PerfSessionContract.parseCompleteOn(intent.getStringExtra(PerfSessionContract.EXTRA_COMPLETE_ON))
            val maxDurationMillis =
                intent.getStringExtra(PerfSessionContract.EXTRA_MAX_MS)?.toLongOrNull()?.coerceIn(1_000, 600_000)
                    ?: DEFAULT_MAX_MILLIS
            val now = SystemClock.elapsedRealtimeNanos()
            val state =
                PerfSessionState(
                    runId = intent.getStringExtra(PerfSessionContract.EXTRA_RUN_ID)?.take(64) ?: "run",
                    scenario = intent.getStringExtra(PerfSessionContract.EXTRA_SCENARIO)?.take(64) ?: "adhoc",
                    iteration = intent.getStringExtra(PerfSessionContract.EXTRA_ITERATION)?.take(16) ?: "1",
                    completeOn = completeOn,
                    deadlineNanos = now + maxDurationMillis * 1_000_000,
                    processStartNanos = PerfEnvironment.processStartElapsedNanos(),
                    startedAtWallClockMillis = System.currentTimeMillis(),
                )
            val session =
                PerfSessionRunner(
                    state = state,
                    appContext = activity.applicationContext,
                    appVersion = PerfEnvironment.appVersion(),
                    refreshRateHz = PerfEnvironment.refreshRateHz(activity),
                )
            runner = session
            session.start(activity.window, maxDurationMillis)
            mergePendingPreSession(session)
            return true
        }
    }

    /** Routes an instrumented event to the live session (measuring its own call cost). */
    fun offerEvent(
        name: String,
        durationMillis: Long? = null,
        detail: Map<String, String> = emptyMap(),
    ): Boolean {
        val start = System.nanoTime()
        val session = synchronized(lock) { runner }
        var completed = false
        if (session != null) {
            completed = session.offerEvent(name, durationMillis, detail)
            session.recordCallCost(start)
        } else if (name in PerfSessionContract.PRE_SESSION_EVENTS) {
            synchronized(lock) {
                if (pendingPreSession.size >= PerfSessionContract.PRE_SESSION_BUFFER_CAP) pendingPreSession.removeFirst()
                pendingPreSession.addLast(PerfRecordedEvent(name, SystemClock.elapsedRealtimeNanos(), durationMillis, detail))
            }
        }
        return completed
    }

    fun setScreenTag(tag: String) {
        synchronized(lock) { runner }?.let { it.screenTag = tag.take(32) }
    }

    fun submitMemorySnapshot(label: String) {
        synchronized(lock) { runner }?.submitMemorySnapshot(label)
    }

    fun recordInteraction() {
        offerEvent(PerfSessionContract.EVENT_INTERACTION)
    }

    fun onActivityDestroyed() {
        val session =
            synchronized(lock) {
                val current = runner
                runner = null
                current
            } ?: return
        session.finish("activity_destroyed")
        session.close(null)
    }

    private fun mergePendingPreSession(session: PerfSessionRunner) {
        while (pendingPreSession.isNotEmpty()) {
            val event = pendingPreSession.removeFirst()
            session.state.offerEvent(event.name, event.tNanos, event.durationMillis, event.detail)
        }
    }

    private const val DEFAULT_MAX_MILLIS = 60_000L
}
