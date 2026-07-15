package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @get:Rule
    val notificationRule: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    @Test
    fun startupReachesReadyLibraryInsteadOfFailureScreen() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.assertEventuallyShows("Library")
            scenario.onActivity { activity ->
                assertFalse(activity.visibleTexts().contains(STARTUP_FAILURE))
            }
        }
    }

    @Test
    fun instrumentationTargetUsesAnIsolatedApplicationSandbox() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(BuildConfig.DEBUG)
        assertTrue(context.packageName.endsWith(".instrumentation"))
        assertTrue(context.filesDir.absolutePath.contains(context.packageName))
        assertFalse(context.packageName.endsWith(".debug"))
    }

    @Test
    fun everyNoArgumentDebugRouteReachesItsScreen() {
        val routes =
            mapOf(
                "library" to "Library",
                "queue" to "Downloads",
                "settings" to "Settings",
                "notifications" to "Notifications",
                "updates" to "Updates",
                "addstory" to "Add Story",
            )

        routes.forEach { (route, title) ->
            ActivityScenario.launch<MainActivity>(launchIntent(route)).use { scenario ->
                scenario.assertEventuallyShows(title)
                scenario.onActivity { activity ->
                    assertFalse("$route displayed startup failure", activity.visibleTexts().contains(STARTUP_FAILURE))
                }
            }
        }
    }

    @Test
    fun storyRoutesWithAnUnknownExplicitStoryFallBackToLibrary() {
        listOf("reader", "details").forEach { route ->
            ActivityScenario.launch<MainActivity>(launchIntent(route, storyId = "missing-instrumentation-story")).use { scenario ->
                scenario.assertEventuallyShows("Library")
            }
        }
    }

    @Test
    fun readerAndDetailsRoutesResolveAPersistedIsolatedFixture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = context.appContainer.repository
        val fixture =
            Story(
                id = FIXTURE_STORY_ID,
                title = "Instrumentation Fixture Novel",
                author = "AndroidJUnit4",
                sourceUrl = "https://example.invalid/instrumentation-fixture",
                totalChapters = 1,
                downloadedChapters = 1,
                chapters =
                    mutableListOf(
                        Chapter(
                            id = FIXTURE_CHAPTER_ID,
                            title = "Instrumentation Fixture Chapter",
                            url = "https://example.invalid/instrumentation-fixture/chapter",
                            content = "<p>Offline fixture content.</p>",
                            downloaded = true,
                        ),
                    ),
            )

        try {
            ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
                scenario.assertEventuallyShows("Library")
            }
            runBlocking { repository.upsertStory(fixture) }

            ActivityScenario.launch<MainActivity>(launchIntent("details", FIXTURE_STORY_ID)).use { scenario ->
                scenario.assertEventuallyShows(fixture.title)
            }
            ActivityScenario
                .launch<MainActivity>(launchIntent("reader", FIXTURE_STORY_ID, FIXTURE_CHAPTER_ID))
                .use { scenario -> scenario.assertEventuallyShows("Instrumentation Fixture Chapter") }
        } finally {
            runBlocking { repository.deleteStory(FIXTURE_STORY_ID) }
        }
    }

    @Test
    fun currentRouteSurvivesActivityRecreationWithoutAnIntentHint() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.assertEventuallyShows("Library")
            scenario.onActivity { activity -> activity.showSettings() }
            scenario.assertEventuallyShows("Settings")
            scenario.recreate()
            scenario.assertEventuallyShows("Settings")
        }
    }

    private companion object {
        const val STARTUP_FAILURE = "The library could not be loaded. Restart the app to try again."
        const val FIXTURE_STORY_ID = "instrumentation-fixture-story"
        const val FIXTURE_CHAPTER_ID = "instrumentation-fixture-chapter"

        fun launchIntent(
            route: String? = null,
            storyId: String? = null,
            chapterId: String? = null,
        ): Intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                route?.let { putExtra(DevLaunchPlanning.EXTRA_DEV_START_SCREEN, it) }
                storyId?.let { putExtra(DevLaunchPlanning.EXTRA_DEV_START_STORY, it) }
                chapterId?.let { putExtra(DevLaunchPlanning.EXTRA_DEV_START_CHAPTER, it) }
            }
    }
}

private fun ActivityScenario<MainActivity>.assertEventuallyShows(
    expectedText: String,
    timeoutMillis: Long = 15_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var latestTexts: List<String> = emptyList()
    while (System.currentTimeMillis() < deadline) {
        onActivity { activity -> latestTexts = activity.visibleTexts() }
        if (expectedText in latestTexts) return
        Thread.sleep(50)
    }
    throw AssertionError("Expected visible text '$expectedText'; latest visible texts were $latestTexts")
}

private fun MainActivity.visibleTexts(): List<String> {
    val texts = mutableListOf<String>()

    fun collect(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) {
            view.text
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(texts::add)
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> collect(view.getChildAt(index)) }
        }
    }
    collect(window.decorView)
    return texts
}
