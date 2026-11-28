package com.vinicius741.webnovelarchiver.data.repository

// Narrow queries over the published library. They return values, never a model instance, so they
// skip the deep copy [AppRepository.story] makes (every chapter of the story) on each call.

/** Whether the library holds the story. */
fun AppRepository.hasStory(storyId: String): Boolean = readOnlyStory(storyId) != null

/** The story's title, or null when it is not in the library. */
fun AppRepository.storyTitle(storyId: String): String? = readOnlyStory(storyId)?.title

/** Whether the story is in the library and still lists the chapter. */
fun AppRepository.hasChapter(
    storyId: String,
    chapterId: String,
): Boolean = readOnlyStory(storyId)?.chapters?.any { it.id == chapterId } == true

/** The chapter's title, or null when the story or the chapter is not in the library. */
fun AppRepository.chapterTitle(
    storyId: String,
    chapterId: String,
): String? =
    readOnlyStory(storyId)
        ?.chapters
        ?.firstOrNull { it.id == chapterId }
        ?.title
