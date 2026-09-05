package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Minimal persistence contract used by repository transaction/cache tests. */
internal interface RepositoryStoryStore {
    val transactionLock: Any

    fun stories(): List<Story>

    fun story(id: String): Story?

    fun addOrUpdateStory(story: Story)

    fun deleteStory(id: String)

    fun saveLibrary(stories: List<Story>)

    fun queue(): List<DownloadJob>

    fun saveQueue(jobs: List<DownloadJob>)

    fun displayPreferences(): DisplayPreferences = DisplayPreferences()

    fun saveDisplayPreferences(preferences: DisplayPreferences) {}
}
