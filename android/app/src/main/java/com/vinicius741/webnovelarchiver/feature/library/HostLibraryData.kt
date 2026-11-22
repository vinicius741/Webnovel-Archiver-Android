package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.flow.StateFlow

/** [LibraryData] over the activity's repository, narration and playback, and its download and listen starts. */
internal class HostLibraryData(
    private val host: ScreenHost,
) : LibraryData {
    private val repository get() = host.repository

    override val downloads: StateFlow<DownloadUiSnapshot> get() = repository.downloadState

    override val playback: StateFlow<TtsPlaybackUpdate> get() = host.ttsEngine.playbackState

    override fun tabs(): List<Tab> = repository.tabs.get()

    override fun displayPreferences(): DisplayPreferences = repository.getDisplayPreferences()

    override suspend fun updateDisplayPreferences(block: (DisplayPreferences) -> DisplayPreferences) =
        repository.updateDisplayPreferences(block)

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun saveStory(story: Story) = repository.addOrUpdateStory(story)

    override suspend fun deleteStory(id: String) {
        repository.deleteStory(id)
        host.narration.deleteStory(id)
    }

    override suspend fun listeningPositions(): List<TtsStoryPosition> = repository.getTtsStoryPositions()

    override fun queueDownload(
        story: Story,
        indexes: List<Int>,
    ) = host.queueDownload(story, indexes)

    override fun playIfGenerated(
        storyId: String,
        chapterId: String,
        onStart: () -> Unit,
    ) = host.playIfGenerated(storyId, chapterId) {
        onStart()
        TtsForegroundService.start(host.app, storyId, chapterId)
    }
}
