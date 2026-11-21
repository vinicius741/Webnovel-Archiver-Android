package com.vinicius741.webnovelarchiver.app

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
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
import com.vinicius741.webnovelarchiver.feature.ai.showAiControls
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                assertEquals(
                    "Keep this pending prompt",
                    activity.appContainer.aiAttention.state.value.coverPrompts[ID]
                        ?.text,
                )
                activity.showDetails(ID)
                activity.showAiControls(ID)
                assertEquals("Keep this pending prompt", activity.promptField().text.toString())
                assertEquals(
                    "Keep this pending prompt",
                    activity.appContainer.aiAttention.state.value.coverPrompts[ID]
                        ?.text,
                )
            }
            // The strip loads asynchronously; the source choice is present and not in use (the AI cover is showing).
            scenario.eventually { it.coverChoice("Source") != null }
            // Tapping a choice opens the full-screen viewer (its own dialog window); "Use this cover" commits it.
            scenario.onActivity { activity -> activity.coverChoice("Source")!!.performClick() }
            scenario.eventually { windowView<TextView> { it.text.toString() == "Use this cover" } != null }
            scenario.onActivity { windowView<TextView> { it.text.toString() == "Use this cover" }!!.performClick() }
            scenario.eventually { !it.repository.story(ID)!!.showAiCover }
            scenario.eventually { windowView<TextView> { it.text.toString() == "In use" } != null }
            scenario.onActivity { activity ->
                // Choosing a display cover never rewrites which AI cover file is on record.
                assertEquals("fixture.png", activity.repository.story(ID)!!.aiCoverPath)
                windowView<View> { it.contentDescription == "Close" }!!.performClick()
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
            var root: View? = null
            scenario.onActivity { activity ->
                activity.operations.setAi(StoryOperationState(ID, StoryOperationKind.AI_DESCRIPTION, "Starting"))
                activity.showAiControls(ID, AiControlsTab.DESCRIPTION)
            }
            scenario.eventually { it.textView("Starting") != null }
            scenario.onActivity { activity ->
                root = activity.frame.getChildAt(0)
                assertFalse(activity.textView("Generating...")!!.isEnabled)
                repeat(100) { activity.operations.updateAi(ID, StoryOperationKind.AI_DESCRIPTION, "Message $it") }
            }
            // Progress patches the line in place; the description card stays gated for the whole run.
            scenario.eventually { it.textView("Message 99") != null }
            scenario.onActivity { activity ->
                assertSame(root, activity.frame.getChildAt(0))
                assertFalse(activity.textView("Generating...")!!.isEnabled)
                // A cover job changes the busy kind; the tab that shows it re-renders.
                activity.operations.clearAi(ID, StoryOperationKind.AI_DESCRIPTION)
                activity.operations.setAi(StoryOperationState(ID, StoryOperationKind.AI_COVER, "Cover starting"))
                activity.showAiControls(ID, AiControlsTab.COVER)
            }
            scenario.eventually { it.textView("Cover starting") != null }
            scenario.onActivity { activity ->
                assertNotSame(root, activity.frame.getChildAt(0))
                root = activity.frame.getChildAt(0)
                assertFalse(activity.promptField().isEnabled)
                repeat(100) { activity.operations.updateAi(ID, StoryOperationKind.AI_COVER, "Cover $it") }
            }
            scenario.eventually { it.textView("Cover 99") != null }
            scenario.onActivity { activity ->
                assertSame(root, activity.frame.getChildAt(0))
                // Off the AI tab, late messages cannot patch a detached tree.
                activity.showDetails(ID)
                root = activity.frame.getChildAt(0)
                activity.operations.updateAi(ID, StoryOperationKind.AI_COVER, "Late")
            }
            scenario.onActivity { activity ->
                assertSame(root, activity.frame.getChildAt(0))
                activity.operations.clearAi(ID, StoryOperationKind.AI_COVER)
                activity.showAiControls(ID, AiControlsTab.DESCRIPTION)
            }
            scenario.eventually { it.textView("Generate Description with AI")?.isEnabled == true }
            scenario.recreate()
            scenario.eventually { (it.navigator.current as? AppRoute.AiControls)?.storyId == ID && it.promptField().isShown }
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
                // JSON export was removed in November 2026; only the full ZIP round trip remains.
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

/** Every visible view in all of the app's windows, including dialogs such as the cover viewer. */
@SuppressLint("NewApi") // WindowInspector is API 29+; these tests run on the API 36 emulator.
private fun allWindowViews(): List<View> {
    fun descendants(view: View): List<View> =
        if (view.visibility != View.VISIBLE) {
            emptyList()
        } else {
            listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        }
    return WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
}

private inline fun <reified T : View> windowView(match: (T) -> Boolean): T? = allWindowViews().filterIsInstance<T>().firstOrNull(match)

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
