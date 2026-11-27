package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A screen model's quick story edits (see [StoryEditPlanning]). [commit] shows an edit at once,
 * through [onChange], and writes it after the model's earlier writes, so they reach storage in tap
 * order. The model draws the story through [shown]. Call it from the model's main-thread scope.
 *
 * The storage transaction can wait behind download commits, sync and narration saves, and then
 * rewrites the whole story file; the screen no longer waits for it.
 */
class OptimisticStoryEdits(
    private val onChange: () -> Unit,
) {
    private var pending = PendingStoryEdits()
    private val writes = Mutex()
    private var shownBase: Story? = null
    private var shownPending: PendingStoryEdits? = null
    private var shownStory: Story? = null

    /** [base] as the screen shows it; the same instance while neither [base] nor the pending edits change. */
    fun shown(base: Story?): Story? {
        if (pending.isEmpty()) return base
        if (base !== shownBase || pending !== shownPending) {
            shownBase = base
            shownPending = pending
            shownStory = StoryEditPlanning.shown(base, pending)
        }
        return shownStory
    }

    /**
     * Shows [edit] now and runs [write] once the earlier writes end. False when [write] reported the
     * edit rejected or threw. Either way the edit is then dropped, so the story storage publishes
     * shows again: the saved edit, or the story as it was.
     */
    suspend fun commit(
        edit: StoryEdit,
        write: suspend () -> Boolean,
    ): Boolean {
        val id = pending.nextId
        pending = pending.plus(edit)
        onChange()
        try {
            return inOrder(write)
        } finally {
            pending = pending.settle(id)
            onChange()
        }
    }

    /** Runs a write with nothing to show (a preference) in the same order; false when it threw. */
    suspend fun save(write: suspend () -> Unit): Boolean =
        inOrder {
            write()
            true
        }

    /** A write the user saw must reach storage, so closing the screen does not cancel it. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun inOrder(write: suspend () -> Boolean): Boolean =
        try {
            withContext(NonCancellable) { writes.withLock { write() } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Could not save a story edit")
            false
        }
}
