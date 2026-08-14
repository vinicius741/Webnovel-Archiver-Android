package com.vinicius741.webnovelarchiver.app

import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthIssue
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthKind
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DevLibraryReportPlanningTest {
    @Test
    fun requestedAcceptsOneOrTrueCaseInsensitiveAndTrimmed() {
        assertTrue(DevLibraryReportPlanning.requested("1"))
        assertTrue(DevLibraryReportPlanning.requested("true"))
        assertTrue(DevLibraryReportPlanning.requested(" TRUE "))
        assertFalse(DevLibraryReportPlanning.requested(null))
        assertFalse(DevLibraryReportPlanning.requested(""))
        assertFalse(DevLibraryReportPlanning.requested("0"))
        assertFalse(DevLibraryReportPlanning.requested("false"))
        assertFalse(DevLibraryReportPlanning.requested("yes"))
    }

    @Test
    fun storyIdsSha256MatchesUtf8JoinedByNewline() {
        val ids = listOf("rr_1", "sh_2", "rr_1__archive_3")
        val expected =
            MessageDigest
                .getInstance("SHA-256")
                .digest("rr_1\nsh_2\nrr_1__archive_3".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertEquals(expected, DevLibraryReportPlanning.storyIdsSha256(ids))
    }

    @Test
    fun buildSummarizesLibraryTabsChaptersAndStorageIssues() {
        val library =
            listOf(
                story("rr_1", tabId = "t_a", downloadedChapters = 2, plainChapters = 1),
                story("rr_2", tabId = "t_a", downloadedChapters = 0, plainChapters = 4),
                story("sh_3", tabId = "t_b", downloadedChapters = 1, plainChapters = 0),
                story("sb_4", tabId = null, downloadedChapters = 0, plainChapters = 2),
            )
        val tabs =
            listOf(
                Tab(id = "t_b", name = "B", order = 1),
                Tab(id = "t_a", name = "A", order = 0),
            )
        val issues =
            listOf(
                StorageHealthIssue("stories/rr_2.json", StorageHealthKind.Corrupt, "quarantined", recoveredStoryCount = 1),
            )

        val report =
            DevLibraryReportPlanning.build(
                library = library,
                tabs = tabs,
                storageIssues = issues,
                appVersion = "1.0.2-native",
                generatedAt = 42L,
            )

        assertEquals(4, report.librarySize)
        assertEquals(DevLibraryReportPlanning.storyIdsSha256(listOf("rr_1", "rr_2", "sh_3", "sb_4")), report.storyIdsSha256)
        assertEquals("rr_1", report.firstStoryId)
        assertEquals("sb_4", report.lastStoryId)
        assertEquals(3 + 4 + 1 + 2, report.totalChapterEntries)
        assertEquals(3, report.downloadedChapterEntries)
        assertEquals(
            listOf(
                DevLibraryReportPlanning.DevLibraryTabReport("t_a", "A", 0, storyCount = 2),
                DevLibraryReportPlanning.DevLibraryTabReport("t_b", "B", 1, storyCount = 1),
            ),
            report.tabs,
        )
        assertEquals(1, report.untabbedStories)
        assertEquals(
            listOf(DevLibraryReportPlanning.DevStorageIssueReport("stories/rr_2.json", "Corrupt", "quarantined", 1)),
            report.storageIssues,
        )
    }

    @Test
    fun buildHandlesEmptyLibrary() {
        val report =
            DevLibraryReportPlanning.build(
                library = emptyList(),
                tabs = emptyList(),
                storageIssues = emptyList(),
                appVersion = null,
            )
        assertEquals(0, report.librarySize)
        assertNull(report.firstStoryId)
        assertNull(report.lastStoryId)
        assertEquals(0, report.totalChapterEntries)
        assertEquals(0, report.downloadedChapterEntries)
        assertTrue(report.storageIssues.isEmpty())
    }

    @Test
    fun toJsonExposesTheVerificationFields() {
        val report =
            DevLibraryReportPlanning.build(
                library = listOf(story("rr_1", tabId = "t_a", downloadedChapters = 0, plainChapters = 3)),
                tabs = listOf(Tab(id = "t_a", name = "A", order = 0)),
                storageIssues = emptyList(),
                appVersion = "1.0.2-native",
            )
        val root = JsonParser.parseString(DevLibraryReportPlanning.toJson(report)).asJsonObject
        assertEquals(1, root["librarySize"].asInt)
        assertEquals(report.storyIdsSha256, root["storyIdsSha256"].asString)
        assertEquals(3, root["totalChapterEntries"].asInt)
        assertEquals(0, root["downloadedChapterEntries"].asInt)
        assertEquals("rr_1", root["firstStoryId"].asString)
        assertEquals(1, root["tabs"].asJsonArray.size())
        assertTrue(root["storageIssues"].asJsonArray.isEmpty)
    }

    private fun story(
        id: String,
        tabId: String?,
        downloadedChapters: Int,
        plainChapters: Int,
    ): Story =
        Story(
            id = id,
            title = "title $id",
            tabId = tabId,
            chapters =
                mutableListOf<Chapter>().apply {
                    repeat(downloadedChapters) { index -> add(Chapter(id = "d$id-$index", downloaded = true)) }
                    repeat(plainChapters) { index -> add(Chapter(id = "p$id-$index")) }
                },
        )
}
