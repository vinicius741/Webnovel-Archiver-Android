package com.vinicius741.webnovelarchiver.core

/** Serializes the queue read-plan-write transaction shared by Activity and service engines. */
object DownloadEnqueueTransaction {
    fun execute(
        lock: Any,
        story: Story,
        indexes: List<Int>,
        now: Long,
        readQueue: () -> List<DownloadJob>,
        readStory: (String) -> Story?,
        persist: (Story, List<DownloadJob>) -> Unit,
    ): QueueChapterPlan =
        synchronized(lock) {
            // The UI Story may predate a service completion. Always plan and persist from the latest
            // durable Story so enqueue metadata cannot erase downloaded/filePath chapter updates.
            val currentStory = readStory(story.id) ?: story
            val plan = DownloadQueuePlanning.queueChapters(readQueue(), currentStory, indexes)
            if (plan.changed) {
                currentStory.status = DownloadStatus.downloading
                currentStory.lastUpdated = now
                persist(currentStory, plan.jobs)
            }
            plan
        }
}
