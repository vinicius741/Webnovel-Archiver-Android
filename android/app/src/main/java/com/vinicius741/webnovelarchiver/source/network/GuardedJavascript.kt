package com.vinicius741.webnovelarchiver.source.network

import android.os.Handler
import android.webkit.WebView

/**
 * `evaluateJavascript` that always ends in exactly one of [evaluate]'s two callbacks.
 *
 * A WebView can leave a script unanswered: on the owner's phone a page-state poll issued while a
 * navigation was committing never called back. Each poll scheduled the next one from its answer,
 * so polling stopped, the render ran into its time limit, and the source was paused as blocked
 * although the page had loaded. Here an answer that has not arrived within [timeoutMillis] counts
 * as lost and a late one is ignored. Main thread only.
 */
internal class GuardedJavascript(
    private val handler: Handler,
    private val timeoutMillis: Long = ANSWER_TIMEOUT_MILLIS,
) {
    fun evaluate(
        web: WebView,
        script: String,
        onLost: () -> Unit,
        onAnswer: (String?) -> Unit,
    ) {
        var settled = false
        val watchdog =
            Runnable {
                if (!settled) {
                    settled = true
                    onLost()
                }
            }
        handler.postDelayed(watchdog, timeoutMillis)
        web.evaluateJavascript(script) { answer ->
            if (!settled) {
                settled = true
                handler.removeCallbacks(watchdog)
                onAnswer(answer)
            }
        }
    }

    private companion object {
        const val ANSWER_TIMEOUT_MILLIS = 2_000L
    }
}
