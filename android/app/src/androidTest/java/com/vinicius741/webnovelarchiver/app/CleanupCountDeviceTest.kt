package com.vinicius741.webnovelarchiver.app

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the per-novel completion dialog against real files in the isolated test sandbox. */
@RunWith(AndroidJUnit4::class)
class CleanupCountDeviceTest {
    @Test
    fun completionCountsSentenceAndPresetRemovalsAcrossChaptersAndReapplyingReportsZero() =
        withFixture { context, fixture ->
            ActivityScenario.launch<MainActivity>(launchIntent(context)).use {
                waitForText(fixture.title)
                applyCleanupFromMenu()
                val message = waitForText("Successfully applied text cleanup").text.toString()
                assertTrue(message, message.contains("19 removals. 2 sentence matches and 17 regex matches removed."))
                val repository = context.appContainer.repository
                assertFalse(runBlocking { repository.readChapter(fixture.chapters[0]) }!!.contains("------"))
                assertFalse(runBlocking { repository.readChapter(fixture.chapters[1]) }!!.contains("******"))
                assertTrue(repository.story(ID)!!.epubStale == true)
                clickText("OK")
                applyCleanupFromMenu()
                val secondMessage = waitForText("Successfully applied text cleanup").text.toString()
                assertTrue(secondMessage, secondMessage.contains("0 removals. 0 sentence matches and 0 regex matches removed."))
                clickText("OK")
            }
        }

    @Test
    fun partialErrorReportCountsOnlySuccessfullyProcessedChapters() =
        withFixture(includeMissing = true) { context, fixture ->
            ActivityScenario.launch<MainActivity>(launchIntent(context)).use {
                waitForText(fixture.title)
                applyCleanupFromMenu()
                val message = waitForText("Processed 2 chapters; 1 had errors.").text.toString()
                assertTrue(message, message.contains("19 removals. 2 sentence matches and 17 regex matches removed."))
                clickText("OK")
            }
        }

    private fun withFixture(
        includeMissing: Boolean = false,
        check: (Context, Story) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Device tests must never touch the debug library", context.packageName.endsWith(".instrumentation"))
        val repository = context.appContainer.repository
        val sentences = repository.getSentenceRemovalList()
        val rules = repository.getRegexRules()
        val preset = requireNotNull(RegexCleanupPresets.generate(RegexCleanupPresets.Preset.REPEATED_PUNCTUATION))
        val fixture =
            Story(
                id = ID,
                title = "Cleanup count fixture",
                sourceUrl = "https://example.invalid/cleanup-count",
                totalChapters = if (includeMissing) 3 else 2,
                downloadedChapters = if (includeMissing) 3 else 2,
            )
        val input =
            listOf(
                "<p>Remove this. Before ${"-".repeat(19)} after</p>",
                "<p>Remove this. Stars ${"*".repeat(8)}.</p>",
            )
        try {
            input.forEachIndexed { index, html ->
                val chapter = Chapter(id = "chapter_$index", title = "Chapter ${index + 1}", downloaded = true)
                // Reuse the process-wide storage owner to create these fixture chapter files.
                chapter.filePath = repository.storage.saveChapter(ID, index, chapter, html)
                fixture.chapters.add(chapter)
            }
            if (includeMissing) {
                fixture.chapters.add(Chapter(id = "missing", title = "Missing chapter", downloaded = true))
            }
            runBlocking {
                repository.upsertStory(fixture)
                repository.saveSentenceRemovalList(listOf("Remove this."))
                repository.saveRegexRules(
                    listOf(
                        RegexCleanupRule(
                            id = "cleanup-count-preset",
                            name = preset.name,
                            pattern = preset.pattern,
                            flags = preset.flags,
                            appliesTo = "both",
                        ),
                    ),
                )
            }
            check(context, fixture)
        } finally {
            runBlocking {
                repository.deleteStory(ID)
                repository.saveSentenceRemovalList(sentences)
                repository.saveRegexRules(rules)
            }
            assertEquals(rules, repository.getRegexRules())
        }
    }

    private fun launchIntent(context: Context) =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(DevLaunchPlanning.EXTRA_DEV_START_SCREEN, "details")
            putExtra(DevLaunchPlanning.EXTRA_DEV_START_STORY, ID)
        }

    private fun applyCleanupFromMenu() {
        clickText("More options")
        clickText("Apply Text Cleanup")
        clickText("Apply")
    }

    private fun clickText(text: String) {
        var node = waitForText(text, exact = true)
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue("Could not click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitForText(
        text: String,
        exact: Boolean = false,
    ): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { node ->
                    !exact ||
                        node.text?.toString().equals(text, ignoreCase = true) ||
                        node.contentDescription?.toString().equals(text, ignoreCase = true)
                }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Expected visible text '$text'")
    }

    private companion object {
        const val ID = "cleanup-count-device-fixture"
    }
}
