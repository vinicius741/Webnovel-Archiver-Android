package com.vinicius741.webnovelarchiver.feature.downloads

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.QueueStatusCounts
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

private data class QueueStoryGroup(
    val storyId: String,
    val jobs: List<DownloadJob>,
    val signature: String,
)

internal class QueueGroupAdapter(
    private val host: ScreenHost,
    private val onExpansionChanged: () -> Unit,
) : RecyclerView.Adapter<QueueGroupAdapter.GroupHolder>() {
    private var groups: List<QueueStoryGroup> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = groups[position].storyId.hashCode().toLong()

    override fun getItemCount(): Int = groups.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): GroupHolder =
        GroupHolder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )

    override fun onBindViewHolder(
        holder: GroupHolder,
        position: Int,
    ) {
        val group = groups[position]
        val card = holder.card ?: host.createQueueGroupCard().also { holder.card = it }
        if (card.view.parent !== holder.container) {
            holder.container.removeAllViews()
            holder.container.addView(card.view)
        }
        card.bind(group.jobs, onExpansionChanged)
    }

    fun submitQueue(queue: List<DownloadJob>) {
        val previous = groups
        val next = host.queueGroups(queue)
        groups = next
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = previous.size

                    override fun getNewListSize(): Int = next.size

                    override fun areItemsTheSame(
                        old: Int,
                        new: Int,
                    ): Boolean = previous[old].storyId == next[new].storyId

                    override fun areContentsTheSame(
                        old: Int,
                        new: Int,
                    ): Boolean = previous[old].signature == next[new].signature
                },
            ).dispatchUpdatesTo(this)
    }

    class GroupHolder(
        val container: FrameLayout,
    ) : RecyclerView.ViewHolder(container) {
        var card: QueueGroupCard? = null
    }
}

private fun ScreenHost.queueGroups(queue: List<DownloadJob>): List<QueueStoryGroup> =
    queue
        .groupBy { it.storyId }
        .values
        .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
        .map { jobs ->
            val counts = QueueStatusCounts.from(jobs)
            val expanded = storyExpandOverride[jobs.first().storyId] ?: (counts.hasActive || counts.hasFailed)
            QueueStoryGroup(
                storyId = jobs.first().storyId,
                jobs = jobs,
                signature =
                    buildString {
                        append(expanded)
                        jobs.sortedBy { it.chapterIndex }.forEach { job ->
                            append('|')
                            append(job.id)
                            append(':')
                            append(job.chapterIndex)
                            append(':')
                            append(job.chapter.title)
                            append(':')
                            append(job.status)
                            append(':')
                            append(job.retryCount)
                            append(':')
                            append(job.error.orEmpty())
                            append(':')
                            append(job.errorCategory.orEmpty())
                            append(':')
                            append(job.errorCode.orEmpty())
                            append(':')
                            append(job.nextRetryAt ?: 0L)
                        }
                    },
            )
        }
