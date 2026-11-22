package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.AuthorPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AuthorNovelsUiState(
    /** The novel the screen was opened from, or null once it left the library. */
    val story: Story?,
    /** The author's name as shown. */
    val author: String,
    /** The author's other novels in the library, by title. */
    val others: List<Story>,
)

/**
 * The author page (`AppRoute.AuthorNovels`): the other library novels by the author of [storyId].
 * It reads the published library (shared stories, never copied); a render recomputes it.
 */
internal class AuthorNovelsScreenModel(
    private val storyId: String,
    private val library: () -> List<Story>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<AuthorNovelsUiState> = mutableState.asStateFlow()

    fun refresh(): AuthorNovelsUiState = compute().also { mutableState.value = it }

    private fun compute(): AuthorNovelsUiState {
        val stories = library()
        val story = stories.firstOrNull { it.id == storyId }
        return AuthorNovelsUiState(
            story = story,
            author = story?.author?.trim()?.ifEmpty { null } ?: "Unknown author",
            others = story?.let { AuthorPlanning.otherNovels(it, stories) }.orEmpty(),
        )
    }
}
