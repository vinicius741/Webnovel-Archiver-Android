package com.vinicius741.webnovelarchiver.feature.downloads

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

internal data class QueueJobUi(
    val job: DownloadJob,
    val status: DownloadJobStatus,
    val retryRemainingSeconds: Long?,
)

internal data class QueueWaitUi(
    val providerName: String,
    val storyId: String,
    val remainingSeconds: Long,
)

internal data class QueueStoryGroupUi(
    val storyId: String,
    val jobs: List<QueueJobUi>,
    val expanded: Boolean,
    val waits: List<QueueWaitUi>,
)

internal class QueueGroupAdapter(
    private val host: ScreenHost,
    private val onExpansionChanged: () -> Unit,
) : RecyclerView.Adapter<QueueGroupAdapter.GroupHolder>() {
    private var groups: List<QueueStoryGroupUi> = emptyList()
    private var pacingSnapshots: Collection<DownloadPacingSnapshot> = emptyList()
    private var nowMillis: Long = 0L
    private var queue: List<DownloadJob> = emptyList()

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
        card.bind(group, queue, pacingSnapshots, nowMillis, onExpansionChanged)
    }

    fun submitQueue(
        queue: List<DownloadJob>,
        pacingSnapshots: Collection<DownloadPacingSnapshot> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val previous = groups
        val activeJobIds =
            queue
                .asSequence()
                .filter { it.status in DownloadJobStatus.activeWires }
                .map { it.id }
                .toSet()
        val next = host.queueGroups(queue, pacingSnapshots, nowMillis, activeJobIds)
        this.pacingSnapshots = pacingSnapshots
        this.nowMillis = nowMillis
        this.queue = queue
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
                    ): Boolean = previous[old] == next[new]
                },
            ).dispatchUpdatesTo(this)
    }

    class GroupHolder(
        val container: FrameLayout,
    ) : RecyclerView.ViewHolder(container) {
        var card: QueueGroupCard? = null
    }
}

private fun ScreenHost.queueGroups(
    queue: List<DownloadJob>,
    pacingSnapshots: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
    activeJobIds: Set<String>,
): List<QueueStoryGroupUi> =
    queue
        .groupBy { it.storyId }
        .values
        .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
        .map { jobs ->
            val counts = DownloadCounts.from(jobs)
            val expanded = storyExpandOverride[jobs.first().storyId] ?: (counts.hasActive || counts.hasFailed)
            val providerName = SourceRegistry.getProvider(jobs.first().sourceId, jobs.first().chapter.url)?.name
            QueueStoryGroupUi(
                storyId = jobs.first().storyId,
                jobs =
                    jobs.sortedBy { it.chapterIndex }.map { job ->
                        QueueJobUi(
                            job = job,
                            status = DownloadJobStatus.parse(job.status),
                            retryRemainingSeconds =
                                job.nextRetryAt?.let { retryAt ->
                                    (retryAt - nowMillis + 999L).coerceAtLeast(0L) / 1_000L
                                },
                        )
                    },
                expanded = expanded,
                waits =
                    pacingSnapshots
                        .filter { snapshot ->
                            snapshot.nextRequestAtMillis > nowMillis &&
                                snapshot.providerName == providerName &&
                                snapshot.jobId in activeJobIds &&
                                jobs.any { it.id == snapshot.jobId }
                        }.sortedBy { it.providerName }
                        .map { wait ->
                            QueueWaitUi(
                                providerName = wait.providerName,
                                storyId = wait.storyId,
                                remainingSeconds = (wait.nextRequestAtMillis - nowMillis + 999L) / 1_000L,
                            )
                        },
            )
        }

/** Build the persistent skeleton for a [QueueGroupCard] (built once per recycled holder). */
internal fun ScreenHost.createQueueGroupCard(): QueueGroupCard {
    val chevron = queueChevronIcon(expanded = true) // rotation is set in bind
    val title =
        makeText(app, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    val subtitle =
        makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(2), 0, 0)
        }
    val progressSlot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val actionSlot =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
    val textColumn =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            addView(progressSlot.apply { setPadding(0, dp(Space.XS + 2), 0, 0) })
        }
    val card =
        makeCard(app).apply {
            val header =
                row {
                    addView(chevron)
                    addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(actionSlot)
                }
            header.isClickable = true
            header.isFocusable = true
            header.background = selectableRipple(ThemeManager.colors.onSurface)
            addView(LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }) // body, captured below
        }
    val body = card.getChildAt(card.childCount - 1) as LinearLayout
    card.layoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = app.dp(Space.LG)
        }
    return QueueGroupCard(card, this, card.getChildAt(0) as LinearLayout, chevron, title, subtitle, progressSlot, actionSlot, body)
}

/** Down chevron rotated to point left when a download group is collapsed. */
internal fun ScreenHost.queueChevronIcon(expanded: Boolean): View =
    ImageView(app).apply {
        setImageDrawable(app.tintedIcon(R.drawable.wna_chevron_down, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(Space.XS), dp(Space.XS), dp(Space.XS), dp(Space.XS))
        rotation = if (expanded) 0f else -90f
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }
