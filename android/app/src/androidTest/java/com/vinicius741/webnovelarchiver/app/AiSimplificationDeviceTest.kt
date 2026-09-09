package com.vinicius741.webnovelarchiver.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.ai.patchAiDraftProgress
import com.vinicius741.webnovelarchiver.feature.ai.showAiControls
import com.vinicius741.webnovelarchiver.feature.ai.updateAiControlsProgress
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSimplificationDeviceTest {
    @Test
    fun coverChoicesPersistAndOptionsRetainTheirStoryScope() =
        withFixture { scenario ->
            scenario.onActivity { activity ->
                assertNull(activity.textView("Write your own prompt"))
                activity.textView("Generation options")!!.performClick()
                assertNotNull(activity.textView("Write your own prompt"))
                activity.aiControlsScreenState.coverPrompts[ID] = "Keep this pending prompt"
                activity.showDetails(ID)
                activity.showAiControls(ID)
                assertNotNull(activity.textView("Write your own prompt"))
                activity.textView("Generation options")!!.performClick()
                assertNull(activity.textView("Write your own prompt"))
                assertEquals("Keep this pending prompt", activity.aiControlsScreenState.coverPrompts[ID])
                assertEquals(2, activity.allViews().filterIsInstance<RadioButton>().size)
                activity
                    .allViews()
                    .filterIsInstance<RadioButton>()
                    .single { it.text == "Source" }
                    .performClick()
            }
            scenario.eventually { !it.repository.story(ID)!!.showAiCover }
            scenario.onActivity { activity ->
                activity.showAiControls(ID)
                assertTrue(
                    activity
                        .allViews()
                        .filterIsInstance<RadioButton>()
                        .single { it.text == "Source" }
                        .isChecked,
                )
                assertEquals("fixture.png", activity.repository.story(ID)!!.aiCoverPath)
                activity.textView("Generation options")!!.performClick()
                activity
                    .allViews()
                    .filterIsInstance<CheckBox>()
                    .single {
                        it.parent is android.widget.LinearLayout &&
                            (it.parent as ViewGroup).let { parent ->
                                (0 until parent.childCount).map { i -> parent.getChildAt(i) }.filterIsInstance<TextView>().any { view ->
                                    view.text ==
                                        "Generate prompt + image in one step"
                                }
                            }
                    }.performClick()
            }
            scenario.eventually { it.textView("Try a new AI prompt") != null || it.textView("Try a new cover") != null }
        }

    @Test
    fun unavailableAndArchivedCoverChoicesAreHidden() =
        withFixture { scenario ->
            val repository = ApplicationProvider.getApplicationContext<android.content.Context>().appContainer.repository
            for (fixture in listOf(story().copy(aiCoverPath = null), story().copy(coverUrl = null), story().copy(isArchived = true))) {
                runBlocking { repository.upsertStory(fixture) }
                scenario.onActivity { activity ->
                    activity.showAiControls(ID)
                    assertTrue(activity.allViews().filterIsInstance<RadioButton>().isEmpty())
                    if (fixture.isArchived == true) assertNull(activity.textView("Generation options"))
                }
            }
        }

    @Test
    fun manualSyncFailurePreservesPresentationAndQueuesNothing() =
        withFixture { scenario ->
            val callbacks = mutableListOf<String>()
            scenario.onActivity { activity ->
                activity.syncStory(
                    "https://example.invalid/unsupported",
                    null,
                    onStatus = { callbacks += "status" },
                    onDone = { callbacks += "done" },
                    onError = { callbacks += "error" },
                )
            }
            scenario.eventually { "error" in callbacks }
            scenario.onActivity { activity ->
                assertEquals(listOf("status", "error"), callbacks)
                activity.syncStory(activity.repository.story(ID)!!)
            }
            scenario.eventually { it.storyOperation == null && it.textView("Simplification fixture") != null }
        }

    @Test
    fun progressPatchesTheAttachedTreeAndIgnoresLateMessages() =
        withFixture { scenario ->
            scenario.onActivity { activity ->
                activity.storyOperation = StoryOperationState(ID, StoryOperationKind.AI_DESCRIPTION, "Starting")
                activity.showAiControls(ID)
                val binding = activity.aiControlsScreenState.binding!!
                val root = activity.frame.getChildAt(0)
                activity.textView("Generation options")!!.performClick()
                repeat(100) { activity.patchAiDraftProgress(ID, "Message $it") }
                assertSame(root, activity.frame.getChildAt(0))
                assertSame(binding, activity.aiControlsScreenState.binding)
                assertNotNull(activity.textView("Message 99"))
                assertNotNull(activity.textView("Write your own prompt"))
                assertFalse(activity.textView("Generating...")!!.isEnabled)
                activity.storyOperation = StoryOperationState(ID, StoryOperationKind.AI_COVER, "Cover starting")
                activity.updateAiControlsProgress(activity.storyOperation!!)
                assertNotSame(root, activity.frame.getChildAt(0))
                val coverRoot = activity.frame.getChildAt(0)
                repeat(100) {
                    activity.storyOperation = activity.storyOperation!!.copy(message = "Cover $it")
                    activity.updateAiControlsProgress(activity.storyOperation!!)
                }
                assertSame(coverRoot, activity.frame.getChildAt(0))
                assertNotNull(activity.textView("Cover 99"))
                activity.showDetails(ID)
                assertNull(activity.aiControlsScreenState.binding)
                val detailsRoot = activity.frame.getChildAt(0)
                activity.updateAiControlsProgress(StoryOperationState(ID, StoryOperationKind.AI_COVER, "Late"))
                assertSame(detailsRoot, activity.frame.getChildAt(0))
                activity.storyOperation = null
                activity.showAiControls(ID)
                assertNull(activity.textView("Generating..."))
            }
            scenario.recreate()
            scenario.eventually { it.textView("AI Controls") != null }
        }

    @Test
    fun sourceStateAndChapterSelectionsSurviveJsonAndZipRestore() =
        withFixture { scenario ->
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            assertTrue(context.packageName.endsWith(".instrumentation"))
            val repository = context.appContainer.repository
            val fixture =
                story().copy(
                    aiCoverPath = null,
                    sourceSyncState =
                        com.vinicius741.webnovelarchiver.domain.model.SourceSyncState(
                            lastCheckedAt = 123,
                            consecutiveNotFoundCount = 2,
                        ),
                    aiContextChapterIndices = mutableListOf(0),
                    aiCoverContextChapterIndices = mutableListOf(0),
                    lastReadChapterId = "one",
                    epubPaths = mutableListOf(),
                    chapters =
                        mutableListOf(
                            Chapter(
                                id = "one",
                                title = "One",
                                url = "https://example.invalid/one",
                                downloaded = true,
                                content = "<p>Fixture chapter.</p>",
                            ),
                        ),
                )
            runBlocking {
                repository.upsertStory(fixture)
                val json = repository.exportBackup()
                try {
                    repository.importBackupUri(android.net.Uri.fromFile(json))
                    assertEquals(fixture.sourceSyncState, repository.story(ID)!!.sourceSyncState)
                    val zip = repository.exportFullBackup()
                    try {
                        val result = repository.importFullBackupUri(android.net.Uri.fromFile(zip))
                        assertFalse(result, result.contains("failed", ignoreCase = true))
                        val restored = repository.story(ID)!!
                        assertEquals(fixture.sourceSyncState, restored.sourceSyncState)
                        assertEquals(listOf(0), restored.aiContextChapterIndices)
                        assertEquals(listOf(0), restored.aiCoverContextChapterIndices)
                        assertEquals("one", restored.lastReadChapterId)
                        assertEquals(listOf("one"), restored.chapters.map { it.id })
                        assertEquals("<p>Fixture chapter.</p>", repository.readChapter(restored.chapters.single()))
                    } finally {
                        zip.delete()
                    }
                } finally {
                    json.delete()
                }
            }
            scenario.onActivity { it.showAiControls(ID) }
        }

    private fun withFixture(check: (ActivityScenario<MainActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = context.appContainer.repository
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.eventually { it.textView("Library") != null }
            val settings = repository.getAiSettings()
            try {
                runBlocking { repository.upsertStory(story()) }
                scenario.onActivity { it.showAiControls(ID) }
                check(scenario)
            } finally {
                runBlocking {
                    repository.deleteStory(ID)
                    repository.saveAiSettings(settings)
                }
            }
        }
    }

    private fun story() =
        Story(
            id = ID,
            title = "Simplification fixture",
            author = "Device test",
            sourceUrl = "https://example.invalid/simplification",
            coverUrl = "https://example.invalid/cover.png",
            aiCoverPath = "fixture.png",
            showAiCover = true,
            chapters = mutableListOf(Chapter(id = "one", title = "One", url = "https://example.invalid/one", downloaded = true)),
        )

    private companion object {
        const val ID = "simplification-device-fixture"
    }
}

private fun MainActivity.allViews(): List<View> {
    fun descendants(view: View): List<View> =
        if (view.visibility != View.VISIBLE) {
            emptyList()
        } else {
            listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        }
    return descendants(window.decorView)
}

private fun MainActivity.textView(text: String) = allViews().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }

private fun ActivityScenario<MainActivity>.eventually(predicate: (MainActivity) -> Boolean) {
    val deadline = System.currentTimeMillis() + 15000
    while (System.currentTimeMillis() < deadline) {
        var ready = false
        onActivity { ready = predicate(it) }
        if (ready) return
        Thread.sleep(50)
    }
    throw AssertionError("UI condition did not settle")
}
