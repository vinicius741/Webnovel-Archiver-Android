package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A setting's newest value that a page has not finished saving. It is process-wide because the save
 * runs in the process scope and outlives the page, so the hub, or a quick return to the page, shows
 * it instead of the older saved value. Cleared once its save finishes, whether or not it succeeded.
 */
internal class UnsavedSetting<T : Any> {
    @Volatile
    var value: T? = null
        private set

    private var inFlight: Job? = null

    /** The unsaved value, else [saved]. */
    fun shown(saved: () -> T): T = value ?: saved()

    fun stage(next: T) {
        value = next
    }

    /** [saved] finished saving; it stops being shown unless a newer value came since. */
    fun saved(saved: T) {
        if (value === saved) value = null
    }

    /**
     * Runs [write] on [saveScope] strictly after any earlier queued save on this setting, so an
     * older save can never finish after a newer one and overwrite it — even across screen re-entries.
     */
    fun enqueueSave(
        saveScope: CoroutineScope,
        write: suspend () -> Unit,
    ): Job =
        synchronized(this) {
            val previous = inFlight
            val next =
                saveScope.launch {
                    previous?.join()
                    write()
                }
            inFlight = next
            next
        }
}

/** The values the settings pages have not finished saving (see [UnsavedSetting]). */
internal object UnsavedSettings {
    val downloads = UnsavedSetting<AppSettings>()
    val tts = UnsavedSetting<TtsSettings>()
    val aiKey = UnsavedSetting<String>()
}

/**
 * Saves a page's changes as they happen: each [change] is staged in [unsaved] at once and saved
 * after [delayMs] without another (a held stepper fires many), and [flush] saves a waiting change
 * at once (on leaving the page). Saves run in [saveScope], the process scope, so one started on
 * leaving finishes even if the activity goes away; the wait runs in the page model's [scope].
 */
internal class Autosave<T : Any>(
    private val unsaved: UnsavedSetting<T>,
    private val scope: CoroutineScope,
    private val saveScope: CoroutineScope,
    private val delayMs: Long,
    private val save: suspend (T) -> Unit,
) {
    private var waiting: T? = null
    private var timer: Job? = null

    fun change(next: T) {
        unsaved.stage(next)
        waiting = next
        timer?.cancel()
        timer =
            scope.launch {
                delay(delayMs)
                flush()
            }
    }

    fun flush() {
        timer?.cancel()
        timer = null
        val toSave = waiting ?: return
        waiting = null
        unsaved.enqueueSave(saveScope) {
            try {
                save(toSave)
            } finally {
                unsaved.saved(toSave)
            }
        }
    }
}
