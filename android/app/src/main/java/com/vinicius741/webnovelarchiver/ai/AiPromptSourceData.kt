package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Builds a clearly delimited JSON payload so novel text is data, never part of the instructions. */
internal object AiPromptSourceData {
    fun build(
        story: Story,
        chapters: List<AiDescriptionPlanning.ChapterText>,
        description: String? = null,
    ): String {
        val source =
            JsonObject().apply {
                addProperty("title", story.title.safeSourceValue())
                story.author.takeIf { it.isNotBlank() }?.let { addProperty("author", it.safeSourceValue()) }
                story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    add(
                        "tags",
                        JsonArray().apply { tags.forEach { add(it.safeSourceValue()) } },
                    )
                }
                description?.takeIf { it.isNotBlank() }?.let { addProperty("description", it.safeSourceValue()) }
                add(
                    "chapter_excerpts",
                    JsonArray().apply {
                        chapters.forEach { chapter ->
                            add(
                                JsonObject().apply {
                                    addProperty("downloaded_position", chapter.number)
                                    chapter.title.takeIf { it.isNotBlank() }?.let {
                                        addProperty("title", it.safeSourceValue())
                                    }
                                    addProperty("text", chapter.text.safeSourceValue())
                                },
                            )
                        }
                    },
                )
            }
        return "SOURCE_DATA_START\n$source\nSOURCE_DATA_END"
    }

    private fun String.safeSourceValue(): String =
        replace(Regex("SOURCE_DATA_(START|END)", RegexOption.IGNORE_CASE), "[source boundary marker removed]")
}
