package com.vinicius741.webnovelarchiver.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern

private const val UI_TIMEOUT_MS = 15_000L
private const val POLL_MS = 100L
private const val SWIPE_STEPS = 12

// Library card titles read "Fixture Novel 24".
private val fixtureNovel = Pattern.compile("Fixture Novel \\d+")
private val chapterRow = Pattern.compile("Chapter \\d+: .*")

internal fun MacrobenchmarkScope.coldStartToLibrary() {
    pressHome()
    startActivityAndWait()
    waitFor(By.text(fixtureNovel))
}

internal fun MacrobenchmarkScope.scrollLibrary() {
    // Swipe over the novel grid (in line with a card, clear of the navigation rail).
    val x = waitFor(By.text(fixtureNovel)).visibleCenter.x
    repeat(2) { swipeVertically(x, up = true) }
    swipeVertically(x, up = false)
}

internal fun MacrobenchmarkScope.openFirstStory() {
    waitFor(By.text(fixtureNovel)).click()
    waitFor(By.text("Chapters"))
}

internal fun MacrobenchmarkScope.openChaptersTab() {
    waitFor(By.text("Chapters")).click()
    waitFor(By.text(chapterRow))
}

/** Opens the first downloaded chapter; the Reader is a WebView, so it scrolls by swiping. */
internal fun MacrobenchmarkScope.openReaderAndScroll() {
    waitFor(By.text(chapterRow)).click()
    waitFor(By.text("Next"), By.desc("Reader settings"))
    repeat(3) { swipeVertically(device.displayWidth / 2, up = true) }
    device.pressBack()
    waitFor(By.text(chapterRow))
}

/** Swipes content up (scrolls down) or down at [x], then lets the list settle. */
private fun MacrobenchmarkScope.swipeVertically(
    x: Int,
    up: Boolean,
) {
    val low = device.displayHeight * 3 / 4
    val high = device.displayHeight / 4
    if (up) device.swipe(x, low, x, high, SWIPE_STEPS) else device.swipe(x, high, x, low, SWIPE_STEPS)
    device.waitForIdle()
}

/** The first object matching any of [selectors]; fails the run when none shows up in time. */
private fun MacrobenchmarkScope.waitFor(vararg selectors: BySelector): UiObject2 {
    val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        selectors.firstNotNullOfOrNull { device.findObject(it) }?.let { return it }
        Thread.sleep(POLL_MS)
    }
    error("Timed out waiting for ${selectors.joinToString()}")
}
