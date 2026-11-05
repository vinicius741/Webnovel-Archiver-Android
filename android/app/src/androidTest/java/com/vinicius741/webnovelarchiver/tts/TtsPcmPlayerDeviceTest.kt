package com.vinicius741.webnovelarchiver.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real AudioTrack timing, which unit tests cannot cover. The audio is silent, so nothing is heard. */
@RunWith(AndroidJUnit4::class)
class TtsPcmPlayerDeviceTest {
    private class Feed(
        private val items: List<Long>,
    ) : TtsPcmFeed {
        override val itemCount = items.size

        override fun silenceMs(item: Int): Long? = if (item % 2 == 1) items[item] else null

        override fun awaitClip(
            item: Int,
            timeoutMs: Long,
        ) = TtsPcmClip(RATE, ByteArray(TtsPcmPlanning.msToFrames(items[item], RATE).toInt() * BYTES_PER_FRAME))
    }

    private class Events : TtsPcmPlayer.Listener {
        val started = mutableListOf<Int>()
        val ended = CountDownLatch(1)

        override fun onItemStarted(item: Int) {
            synchronized(started) { started += item }
        }

        override fun onEnded() = ended.countDown()

        override fun onFailed(error: Throwable) = throw AssertionError(error)
    }

    @Test
    fun playsThroughEveryItemAndEnds() {
        val events = Events()
        val player = TtsPcmPlayer().apply { listener = events }
        player.start(Feed(listOf(300L, 100L, 300L, 100L)), 0, 0L, 1f, playWhenReady = true)
        assertTrue("never finished", events.ended.await(10, TimeUnit.SECONDS))
        player.release()
        assertEquals(listOf(0, 1, 2, 3), synchronized(events.started) { events.started.toList() })
    }

    @Test
    fun positionFollowsContentTimeAtDoubleSpeed() {
        val events = Events()
        val player = TtsPcmPlayer().apply { listener = events }
        val began = SystemClock.elapsedRealtime()
        player.start(Feed(listOf(20_000L)), 0, 0L, 2f, playWhenReady = true)
        waitUntil { player.position().offsetMs > 1_000L }
        SystemClock.sleep(2_000L)
        val position = player.position().offsetMs
        val elapsed = SystemClock.elapsedRealtime() - began
        player.release()
        val ratio = position.toDouble() / elapsed
        assertTrue("position $position after $elapsed ms", ratio in 1.5..2.3)
    }

    @Test
    fun aPauseLastsTheSameRealTimeAtDoubleSpeed() {
        val startedAt = mutableMapOf<Int, Long>()
        val ended = CountDownLatch(1)
        val player =
            TtsPcmPlayer().apply {
                listener =
                    object : TtsPcmPlayer.Listener {
                        override fun onItemStarted(item: Int) {
                            synchronized(startedAt) { startedAt[item] = SystemClock.elapsedRealtime() }
                        }

                        override fun onEnded() = ended.countDown()

                        override fun onFailed(error: Throwable) = throw AssertionError(error)
                    }
            }
        player.start(Feed(listOf(1_000L, 1_500L, 1_000L)), 0, 0L, 2f, playWhenReady = true)
        assertTrue("never finished", ended.await(15, TimeUnit.SECONDS))
        player.release()
        val pause = synchronized(startedAt) { startedAt.getValue(2) - startedAt.getValue(1) }
        assertTrue("a 1500 ms pause lasted $pause ms", pause in 1_300L..1_800L)
    }

    @Test
    fun phrasePauseHoldsThePlaceInsideASentence() {
        val loud = ByteArray(TtsPcmPlanning.msToFrames(500L, RATE).toInt() * BYTES_PER_FRAME) { if (it % 2 == 0) 0xC8.toByte() else 0 }
        val gap = ByteArray(TtsPcmPlanning.msToFrames(200L, RATE).toInt() * BYTES_PER_FRAME)
        val clip = TtsPcmClip(RATE, loud + gap + loud)
        val feed =
            object : TtsPcmFeed {
                override val itemCount = 1
                override val phrasePauseMs = 1_000L

                override fun silenceMs(item: Int): Long? = null

                override fun awaitClip(
                    item: Int,
                    timeoutMs: Long,
                ) = clip
            }
        val events = Events()
        val player = TtsPcmPlayer().apply { listener = events }
        val began = SystemClock.elapsedRealtime()
        player.start(feed, 0, 0L, 1f, playWhenReady = true)
        assertTrue("never finished", events.ended.await(10, TimeUnit.SECONDS))
        val elapsed = SystemClock.elapsedRealtime() - began
        val finalOffset = player.position().offsetMs
        player.release()
        assertTrue("1200 ms of audio plus a 1000 ms pause took $elapsed ms", elapsed in 2_000L..3_500L)
        assertTrue("offset $finalOffset should stay within the 1200 ms sentence", finalOffset <= 1_250L)
    }

    @Test
    fun pauseHoldsThePositionAndPlayContinuesFromIt() {
        val player = TtsPcmPlayer().apply { listener = Events() }
        player.start(Feed(listOf(20_000L)), 0, 0L, 1f, playWhenReady = true)
        waitUntil { player.position().offsetMs > 500L }
        player.pause()
        SystemClock.sleep(300L)
        val held = player.position().offsetMs
        SystemClock.sleep(1_000L)
        assertEquals(held, player.position().offsetMs)
        player.play()
        waitUntil { player.position().offsetMs > held + 300L }
        player.release()
    }

    @Test
    fun seekMovesToTheOffsetEvenWhilePaused() {
        val player = TtsPcmPlayer().apply { listener = Events() }
        player.start(Feed(listOf(20_000L, 100L, 20_000L)), 0, 0L, 1f, playWhenReady = false)
        player.seek(2, 5_000L)
        SystemClock.sleep(500L)
        assertEquals(TtsTimelinePoint(2, 5_000L), player.position())
        player.play()
        waitUntil { player.position().offsetMs > 5_200L }
        player.release()
    }

    @Test
    fun seekPastTheLastItemEnds() {
        val events = Events()
        val player = TtsPcmPlayer().apply { listener = events }
        player.start(Feed(listOf(1_000L, 100L)), 1, 50L, 1f, playWhenReady = true)
        assertTrue("never finished", events.ended.await(5, TimeUnit.SECONDS))
        player.release()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition()) {
            assertTrue("condition not reached", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(20L)
        }
    }

    private companion object {
        const val RATE = 24_000
    }
}
