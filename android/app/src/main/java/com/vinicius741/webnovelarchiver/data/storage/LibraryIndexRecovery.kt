package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Story
import java.io.File

data class LibraryIndexRecoveryResult(
    val stories: List<Story>,
    val rejectedFiles: List<File>,
)

/** Reconstructs an in-memory index from valid story documents without writing to the live root. */
object LibraryIndexRecovery {
    fun scan(
        files: List<File>,
        safeName: (String) -> String,
        readStory: (File) -> DurableReadResult<Story>,
    ): LibraryIndexRecoveryResult {
        val storiesById = linkedMapOf<String, Story>()
        val rejected = mutableListOf<File>()
        files
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .forEach { file ->
                val story = (readStory(file) as? DurableReadResult.Present)?.value
                val valid =
                    story != null &&
                        story.id.isNotBlank() &&
                        file.name == "${safeName(story.id)}.json" &&
                        !storiesById.containsKey(story.id)
                if (valid) {
                    storiesById[story.id] = story
                } else {
                    rejected += file
                }
            }
        return LibraryIndexRecoveryResult(storiesById.values.toList(), rejected)
    }
}
