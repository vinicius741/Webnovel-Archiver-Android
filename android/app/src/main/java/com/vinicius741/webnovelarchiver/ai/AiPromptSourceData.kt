package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Builds a clearly delimited JSON payload so novel text is data, never part of the instructions. */
internal object AiPromptSourceData {
    const val METADATA_GUIDANCE = """
        Interpret metadata before using it. The title field may include website labels rather than title words.
        Exclude appended or prefixed genre/trope lists, fandom/crossover labels, SI/OC/CYOA labels,
        release notices, and promotional suffixes from the title you use. Examples:
        "Ace of Capes [Superhero LitRPG] [Isekai] [Card Crafting]" has title "Ace of Capes";
        "A Certain Mental Isekai (Raildex SI)" has title "A Certain Mental Isekai";
        "Amberlin's Apprentice Is Secretly Strong [OP MC, Archmage, Progression] (Arc 1 Complete)"
        has title "Amberlin's Apprentice Is Secretly Strong";
        "[RWBY/The Gamer] The Games We Play" has title "The Games We Play";
        "The Soulweaver - A Soul Stealing LitRPG" has title "The Soulweaver".
        These are cleanup examples only, never facts about the supplied novel.
        Preserve genuine title words and meaningful subtitles, including punctuation or brackets when
        they belong to the title. Do not strip everything after every colon, dash, or opening bracket.
        Ignore scraped website clutter such as "Genre", "Tags", "Rankings#7 in Helpful Protagonist",
        update schedules, ratings, reader appeals, and content-warning labels. Never reproduce those
        labels in the result or treat them as events, character traits, or visual objects.
        Relevant genre tags can inform tone; they do not prove a plot, appearance, or setting detail.
        A fandom label is not permission to import remembered canon that is absent from the excerpts.
    """

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
