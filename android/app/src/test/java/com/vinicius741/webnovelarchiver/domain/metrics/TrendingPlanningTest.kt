package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Candidate
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Reading
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Signal
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingPlanningTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun story(
        id: String,
        chapters: Int = 10,
        bookmark: Int? = null,
    ): Story {
        val list = (1..chapters).map { Chapter(id = "$id-c$it", title = "Chapter $it") }.toMutableList()
        return Story(id = id, title = id, chapters = list, lastReadChapterId = bookmark?.let { list[it - 1].id })
    }

    /** One snapshot per (daysAgo, members) pair. */
    private fun patreon(
        id: String,
        vararg points: Pair<Int, Int>,
    ) = StoryMetricHistory(
        storyId = id,
        snapshots =
            points
                .map { (ago, members) ->
                    StoryMetricSnapshot(capturedAt = now - ago * day, patreonRaw = PatreonRawStats(paidMembers = members))
                }.toMutableList(),
    )

    private fun watchers(
        id: String,
        vararg points: Pair<Int, Long>,
    ) = StoryMetricHistory(
        storyId = id,
        snapshots =
            points
                .map { (ago, value) ->
                    StoryMetricSnapshot(
                        capturedAt = now - ago * day,
                        metrics = mutableListOf(SourceMetric(SourceMetricKind.WATCHERS, value)),
                    )
                }.toMutableList(),
    )

    private fun candidate(
        story: Story,
        history: StoryMetricHistory,
        creator: String? = null,
    ) = Candidate(story, history, history, creator)

    @Test
    fun `a recent burst outranks the same burst three weeks ago`() {
        val fresh = candidate(story("fresh"), patreon("fresh", 4 to 100, 1 to 160))
        val old = candidate(story("old"), patreon("old", 24 to 100, 21 to 160, 1 to 160))
        val result = TrendingPlanning.plan(listOf(old, fresh), now)
        assertEquals(
            "fresh",
            result.entries
                .first()
                .story.id,
        )
        val oldHeat = result.entries.firstOrNull { it.story.id == "old" }?.heat ?: 0.0
        assertTrue(result.entries.first().heat > oldHeat * 3)
    }

    @Test
    fun `the burst fades as time passes without new growth`() {
        val history = patreon("s", 4 to 100, 1 to 160)
        val today =
            TrendingPlanning.decayedGrowth(
                Signal.PATREON,
                MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MEMBERS),
                now,
            )
        val later =
            TrendingPlanning.decayedGrowth(
                Signal.PATREON,
                MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MEMBERS),
                now + TrendingPlanning.HALF_LIFE_DAYS.toLong() * day,
            )
        assertEquals(today / 2, later, today * 1e-6)
    }

    @Test
    fun `caught-up novels are hidden and counted`() {
        val caughtUp = candidate(story("done", chapters = 5, bookmark = 5), patreon("done", 4 to 100, 1 to 200))
        val behind = candidate(story("behind", chapters = 5, bookmark = 2), patreon("behind", 4 to 100, 1 to 150))
        val result = TrendingPlanning.plan(listOf(caughtUp, behind), now)
        assertEquals(listOf("behind"), result.entries.map { it.story.id })
        assertEquals(1, result.caughtUpHidden)
        assertEquals(Reading.Behind(3), result.entries.single().reading)
    }

    @Test
    fun `reading state reports not started and unread chapters`() {
        assertEquals(Reading.NotStarted(10), TrendingPlanning.reading(story("a")))
        assertEquals(Reading.Behind(7), TrendingPlanning.reading(story("b", bookmark = 3)))
        assertTrue(TrendingPlanning.isCaughtUp(story("c", bookmark = 10)))
        assertFalse(TrendingPlanning.isCaughtUp(story("d", bookmark = 9)))
    }

    @Test
    fun `each signal is scaled within its own kind so Patreon cannot fill the list`() {
        val patreonNovels =
            (1..6).map { candidate(story("p$it"), patreon("p$it", 5 to 1000, 1 to 1000 + it * 200)) }
        val readerNovels =
            (1..6).map { candidate(story("r$it"), watchers("r$it", 5 to 2000L, 1 to 2000L + it * 40)) }
        val result = TrendingPlanning.plan(patreonNovels + readerNovels, now)
        val top =
            result.entries
                .take(6)
                .map { it.lead.signal }
                .toSet()
        assertEquals(setOf(Signal.PATREON, Signal.AUDIENCE), top)
    }

    @Test
    fun `small numbers are smoothed so two to five patrons does not beat hundreds`() {
        val tiny = candidate(story("tiny"), patreon("tiny", 4 to 2, 1 to 5))
        val big = candidate(story("big"), patreon("big", 4 to 1000, 1 to 1400))
        val result = TrendingPlanning.plan(listOf(tiny, big), now)
        assertEquals(
            "big",
            result.entries
                .first()
                .story.id,
        )
    }

    @Test
    fun `a recovery from a dip is not a rise`() {
        val dip = candidate(story("dip"), patreon("dip", 13 to 200, 6 to 120, 1 to 180))
        val rise = candidate(story("rise"), patreon("rise", 13 to 100, 1 to 140))
        val result = TrendingPlanning.plan(listOf(dip, rise), now)
        assertEquals(listOf("rise"), result.entries.map { it.story.id })
    }

    @Test
    fun `novels sharing one creator share its Patreon growth once`() {
        val history = patreon("shared", 4 to 100, 1 to 200)
        val first = candidate(story("first"), history, creator = "creator")
        val second = candidate(story("second"), history, creator = "creator")
        val result = TrendingPlanning.plan(listOf(first, second), now)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `the lead movement measures from the last snapshot at or before the window start`() {
        val result = TrendingPlanning.plan(listOf(candidate(story("s"), patreon("s", 30 to 50, 10 to 100, 1 to 180))), now)
        val lead = result.entries.single().lead
        assertEquals(Signal.PATREON, lead.signal)
        assertEquals(130.0, lead.change, 1e-9)
        assertEquals(29.0, lead.changeDays, 1e-9)
        assertEquals(180.0, lead.latest, 1e-9)
    }

    @Test
    fun `a novel with a single snapshot cannot trend`() {
        val result = TrendingPlanning.plan(listOf(candidate(story("s"), patreon("s", 1 to 100))), now)
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.measured)
    }
}
