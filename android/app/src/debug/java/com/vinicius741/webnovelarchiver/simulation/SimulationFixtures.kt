package com.vinicius741.webnovelarchiver.simulation

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Synthetic text and stable provider identities, never a copy of the user's library. */
internal object SimulationFixtures {
    const val STORY_ID = "rr_990001"
    const val STORY_URL = "https://www.royalroad.com/fiction/990001/the-clockwork-library"
    const val SECOND_URL = "https://www.royalroad.com/fiction/990002/the-brass-harbor"
    const val OTHER_URL = "https://www.fanfiction.net/s/990003/1/The-Paper-Moon"
    const val DESCRIPTION =
        "Mara repairs clocks in a harbor where every tide erases a memory. When an abandoned library begins " +
            "ticking beneath the docks, she discovers a map drawn in brass and a promise she cannot remember making. " +
            "With her apprentice Ivo, Mara must trace the missing hours before the harbor loses its past."
    const val PROMPT =
        "Paint a portrait book cover titled The Clockwork Library. Mara wears a blue coat and carries a brass clock. " +
            "A moonlit harbor surrounds a library beneath the docks. Use copper, blue and warm lamplight."

    fun stories(count: Int): List<Story> =
        listOf(
            story(STORY_ID, STORY_URL, "The Clockwork Library", count),
            story("rr_990002", SECOND_URL, "The Brass Harbor", count),
            story("ffn_990003", OTHER_URL, "The Paper Moon", count),
        )

    fun forScenario(scenario: SimulationScenario): List<Story> {
        val defaults = stories(scenario.chapterCount)
        if (scenario.faults["source"] != "429") return defaults
        val limited =
            defaults.first().copy(
                id = "sh_990004",
                sourceId = "scribble_hub",
                sourceUrl = "https://www.scribblehub.com/series/990004/offline-clockwork",
                chapters =
                    (1..scenario.chapterCount)
                        .map { index ->
                            Chapter(
                                id = "sh_${99000400 + index}",
                                title = "Chapter $index: The missing hour",
                                url = "https://www.scribblehub.com/read/990004-offline-clockwork/chapter/${99000400 + index}",
                            )
                        }.toMutableList(),
            )
        return listOf(limited) + defaults.drop(1)
    }

    private fun story(
        id: String,
        url: String,
        title: String,
        count: Int,
    ): Story {
        val royal = id.startsWith("rr_")
        val number = id.substringAfter('_').toInt()
        return Story(
            id = id,
            title = title,
            author = "Simulation Author",
            description = DESCRIPTION,
            sourceUrl = url,
            sourceId = if (royal) "royal_road" else "fanfiction_net",
            totalChapters = count,
            chapters =
                (1..count)
                    .map { index ->
                        Chapter(
                            id = if (royal) "${number * 100 + index}" else "ffn_${number}_$index",
                            title = "Chapter $index: The missing hour",
                            url = if (royal) "$url/chapter/${number * 100 + index}/the-missing-hour" else url.replace("/1/", "/$index/"),
                        )
                    }.toMutableList(),
        )
    }

    fun chapterHtml(url: String): String {
        val marker =
            when {
                url.contains("royalroad.com") -> "class=\"chapter-inner\""
                url.contains("scribblehub.com") -> "id=\"chp_raw\""
                else -> "id=\"storytext\""
            }
        val paragraphs =
            (1..12).joinToString("") { index ->
                "<p>Mara lifted her brass clock beneath the blue harbor lamps. " +
                    "Ivo unfolded the library map, tracing route $index toward the missing hour. " +
                    "Her blue coat caught the copper light as she promised to restore the town's memories.</p>"
            }
        return "<!doctype html><html><body><div $marker>$paragraphs</div></body></html>"
    }

    fun storyHtml(story: Story): String {
        val rows = story.chapters.joinToString("") { "<tr class=\"chapter-row\"><td><a href=\"${it.url}\">${it.title}</a></td></tr>" }
        return "<html><head><meta property=\"og:url\" content=\"${story.sourceUrl}\"></head><body>" +
            "<h1>${story.title}</h1><h4><a>Simulation Author</a></h4><div class=\"description\">$DESCRIPTION</div>" +
            "<table>$rows</table></body></html>"
    }
}
