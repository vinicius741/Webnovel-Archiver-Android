package com.vinicius741.webnovelarchiver.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.ai.AiControlsTab
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
    fun coverPromptDraftsAndChoicesRetainTheirStoryScope() =
        withFixture { scenario ->
            scenario.onActivity { activity ->
                val field = activity.promptField()
                field.setText("Keep this pending prompt")
                assertEquals("Keep this pending prompt", activity.aiControlsScreenState.coverPrompts[ID])
                activity.showDetails(ID)
                activity.showAiControls(ID)
                assertEquals("Keep this pending prompt", activity.promptField().text.toString())
                assertEquals("Keep this pending prompt", activity.aiControlsScreenState.coverPrompts[ID])
            }
            // The strip loads asynchronously; the source choice is present and not in use (the AI cover is showing).
            scenario.eventually { it.coverChoice("Source") != null }
            scenario.onActivity { activity -> activity.coverChoice("Source")!!.performClick() }
            scenario.eventually { !it.repository.story(ID)!!.showAiCover }
            scenario.eventually { it.textView("Source · In use") != null }
            scenario.onActivity { activity ->
                // Choosing a display cover never rewrites which AI cover file is on record.
                assertEquals("fixture.png", activity.repository.story(ID)!!.aiCoverPath)
            }
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
                activity.aiOperation = StoryOperationState(ID, StoryOperationKind.AI_DESCRIPTION, "Starting")
                activity.showAiControls(ID, AiControlsTab.DESCRIPTION)
                val binding = activity.aiControlsScreenState.binding!!
                val root = activity.frame.getChildAt(0)
                assertNotNull(activity.textView("Starting"))
                assertFalse(activity.textView("Generating...")!!.isEnabled)
                repeat(100) { activity.patchAiDraftProgress(ID, "Message $it") }
                assertSame(root, activity.frame.getChildAt(0))
                assertSame(binding, activity.aiControlsScreenState.binding)
                assertNotNull(activity.textView("Message 99"))
                // The description card stays gated for the whole run.
                assertFalse(activity.textView("Generating...")!!.isEnabled)
                // A cover operation changes the busy kind and the tab that renders it; switching re-renders.
                activity.aiOperation = StoryOperationState(ID, StoryOperationKind.AI_COVER, "Cover starting")
                activity.showAiControls(ID, AiControlsTab.COVER)
                assertNotSame(root, activity.frame.getChildAt(0))
                val coverBinding = activity.aiControlsScreenState.binding!!
                val coverRoot = activity.frame.getChildAt(0)
                assertNotNull(activity.textView("Cover starting"))
                assertFalse(activity.promptField().isEnabled)
                repeat(100) {
                    activity.aiOperation = activity.aiOperation!!.copy(message = "Cover $it")
                    activity.updateAiControlsProgress(activity.aiOperation!!)
                }
                assertSame(coverRoot, activity.frame.getChildAt(0))
                assertSame(coverBinding, activity.aiControlsScreenState.binding)
                assertNotNull(activity.textView("Cover 99"))
                // Leaving the screen drops the binding; late messages cannot patch a detached tree.
                activity.showDetails(ID)
                assertNull(activity.aiControlsScreenState.binding)
                val detailsRoot = activity.frame.getChildAt(0)
                activity.updateAiControlsProgress(StoryOperationState(ID, StoryOperationKind.AI_COVER, "Late"))
                assertSame(detailsRoot, activity.frame.getChildAt(0))
                assertNull(activity.textView("Late"))
                activity.aiOperation = null
                activity.showAiControls(ID, AiControlsTab.DESCRIPTION)
                assertNull(activity.textView("Generating..."))
                assertTrue(activity.textView("Generate Description with AI")!!.isEnabled)
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
            val settings = repository.aiSettings.get()
            try {
                runBlocking { repository.upsertStory(story()) }
                scenario.onActivity { it.showAiControls(ID) }
                check(scenario)
            } finally {
                runBlocking {
                    repository.deleteStory(ID)
                    repository.aiSettings.save(settings)
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

/** The Cover tab's prompt box ("Make a new cover" card). */
private fun MainActivity.promptField(): EditText =
    allViews()
        .filterIsInstance<EditText>()
        .single { it.contentDescription == "Image prompt" }

/** One strip entry's tappable frame; its contentDescription starts with the choice label ("Source", "AI 1", …). */
private fun MainActivity.coverChoice(label: String): View? =
    allViews()
        .filterIsInstance<FrameLayout>()
        .firstOrNull {
            it.contentDescription?.toString()?.startsWith(label) == true
        }

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
