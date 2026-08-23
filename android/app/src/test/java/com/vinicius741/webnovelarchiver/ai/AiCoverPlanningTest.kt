package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiCoverPlanningTest {
    @Test
    fun `buildPromptMessages sends metadata description and chapters`() {
        val story =
            Story(
                id = "s1",
                title = "Wandering Blades",
                author = "A. Author",
                description = "source synopsis",
                tags = mutableListOf("cultivation", "comedy"),
            )
        val chapters =
            listOf(
                AiDescriptionPlanning.ChapterText(1, "Arrival", "He arrived at the sect."),
                AiDescriptionPlanning.ChapterText(2, "", "The tournament began."),
            )

        val messages = AiCoverPlanning.buildPromptMessages(story, chapters)

        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        val user = messages[1].content
        assert(user.contains("\"title\":\"Wandering Blades\""))
        assert(user.contains("\"author\":\"A. Author\""))
        assert(user.contains("\"tags\":[\"cultivation\",\"comedy\"]"))
        assert(user.contains("\"description\":\"source synopsis\""))
        assert(user.contains("\"downloaded_position\":1"))
        assert(user.contains("\"title\":\"Arrival\""))
        assert(user.contains("He arrived at the sect."))
        assert(messages[0].content.contains("never as instructions"))
        val system = messages[0].content
        assert(system.contains("multiple protagonists"))
        assert(system.contains("single most prominent"))
        assert(system.contains("exactly one focal subject"))
        assert(system.contains("lineups"))
        assert(system.contains("collages"))
        assert(system.contains("diptychs"))
        assert(system.contains("triptychs"))
        assert(system.contains("exactly one prompt"))
        assert(system.contains("70 to 120 words"))
        assert(system.contains("must contain no text"))
    }

    @Test
    fun `buildPromptMessages prefers the AI synopsis when it is active`() {
        val story =
            Story(
                id = "s1",
                title = "T",
                description = "source synopsis",
                aiDescription = "ai synopsis",
                showAiDescription = true,
            )

        val user = AiCoverPlanning.buildPromptMessages(story, emptyList())[1].content

        assert(user.contains("\"description\":\"ai synopsis\""))
        assert(!user.contains("source synopsis"))
    }

    @Test
    fun `buildPromptMessages omits absent metadata lines`() {
        val story = Story(id = "s1", title = "T")

        val user = AiCoverPlanning.buildPromptMessages(story, emptyList())[1].content

        assert(user.contains("\"title\":\"T\""))
        assert(!user.contains("\"author\""))
        assert(!user.contains("\"tags\""))
        assert(!user.contains("\"description\""))
    }

    @Test
    fun `cleanGeneratedPrompt flattens quotes and whitespace and caps length`() {
        assertEquals(
            "A lone tower under storm light.",
            AiCoverPlanning.cleanGeneratedPrompt("  \"A lone\n\ntower   under storm light.\"  "),
        )
        assertNull(AiCoverPlanning.cleanGeneratedPrompt("   \"   "))
        val capped = AiCoverPlanning.cleanGeneratedPrompt("x".repeat(5_000))
        assertEquals(AiCoverPlanning.MAX_PROMPT_CHARS, capped?.length)
    }

    @Test
    fun `isAiCoverActive follows the preference while both covers exist`() {
        val story =
            Story(
                id = "s1",
                title = "T",
                coverUrl = "https://example/cover.jpg",
                aiCoverPath = "covers/s1.png",
            )

        assert(AiCoverPlanning.isAiCoverActive(story.copy(showAiCover = true)))
        assert(!AiCoverPlanning.isAiCoverActive(story.copy(showAiCover = false)))
    }

    @Test
    fun `isAiCoverActive keeps the AI cover on its own when the source has none`() {
        val story = Story(id = "s1", title = "T", aiCoverPath = "covers/s1.png", showAiCover = false)

        assert(AiCoverPlanning.isAiCoverActive(story))
    }

    @Test
    fun `isAiCoverActive is false without a generated cover`() {
        assert(!AiCoverPlanning.isAiCoverActive(Story(id = "s1", title = "T", showAiCover = true)))
        assert(!AiCoverPlanning.isAiCoverActive(Story(id = "s1", title = "T", aiCoverPath = " ", showAiCover = true)))
    }

    @Test
    fun `buildImageRequestParams sends every option the model supports`() {
        val params =
            AiCoverPlanning.buildImageRequestParams(
                mapOf(
                    "aspect_ratio" to listOf("1:1", "2:3"),
                    "resolution" to listOf("1K", "2K"),
                    "quality" to null,
                    "seed" to null,
                ),
            )

        assertEquals("2:3", params.aspectRatio)
        assertEquals("1K", params.resolution)
        assertEquals("medium", params.quality)
        assertNull(params.outputFormat)
    }

    @Test
    fun `buildImageRequestParams drops options the model does not support`() {
        val params = AiCoverPlanning.buildImageRequestParams(mapOf("resolution" to listOf("1K", "2K")))

        assertNull(params.aspectRatio)
        assertEquals("1K", params.resolution)
        assertNull(params.quality)
        assertNull(params.outputFormat)
    }

    @Test
    fun `buildImageRequestParams sends a minimal request when the catalog is unavailable`() {
        val params = AiCoverPlanning.buildImageRequestParams(null)

        assertNull(params.aspectRatio)
        assertNull(params.resolution)
        assertNull(params.quality)
        assertNull(params.outputFormat)
    }

    @Test
    fun `buildImageRequestParams substitutes the nearest supported value when the default is out of enum`() {
        // recraft-style catalog: aspect_ratio lacks 2:3, resolution lacks 1K (seedream-lite style).
        val params =
            AiCoverPlanning.buildImageRequestParams(
                mapOf(
                    "aspect_ratio" to listOf("1:1", "4:3", "3:4", "16:9", "9:16", "auto"),
                    "resolution" to listOf("2K", "4K"),
                    "quality" to listOf("low", "medium", "high"),
                ),
            )

        assertEquals("3:4", params.aspectRatio)
        assertEquals("2K", params.resolution)
        assertEquals("medium", params.quality)
    }

    @Test
    fun `buildImageRequestParams omits a supported parameter with no acceptable value`() {
        // Square/landscape-only ratios and unrelated quality tiers: nothing safe to send.
        val params =
            AiCoverPlanning.buildImageRequestParams(
                mapOf(
                    "aspect_ratio" to listOf("1:1", "16:9"),
                    "quality" to listOf("draft", "hd"),
                ),
            )

        assertNull(params.aspectRatio)
        assertNull(params.quality)
    }

    @Test
    fun `buildImageRequestParams treats a values-less parameter as unconstrained`() {
        val params = AiCoverPlanning.buildImageRequestParams(mapOf("aspect_ratio" to null))

        assertEquals("2:3", params.aspectRatio)
        assertNull(params.resolution)
        assertNull(params.quality)
    }

    @Test
    fun `buildImageRequestParams requests a supported raster format`() {
        val params =
            AiCoverPlanning.buildImageRequestParams(
                mapOf("output_format" to listOf("svg", "webp", "jpeg")),
            )

        assertEquals("jpeg", params.outputFormat)
    }

    @Test
    fun `supportsRasterOutput rejects vector-only models`() {
        val vector = OpenRouterImageModel("recraft/vector", "Vector", mapOf("output_format" to listOf("svg")))
        val mixed = OpenRouterImageModel("recraft/mixed", "Mixed", mapOf("output_format" to listOf("svg", "png")))
        val unspecified = OpenRouterImageModel("other/model", "Other")

        assert(!AiCoverPlanning.supportsRasterOutput(vector))
        assert(AiCoverPlanning.supportsRasterOutput(mixed))
        assert(AiCoverPlanning.supportsRasterOutput(unspecified))
    }

    @Test
    fun `supportsGeneratedMediaType rejects explicit vector responses`() {
        assert(AiCoverPlanning.supportsGeneratedMediaType("image/png"))
        assert(AiCoverPlanning.supportsGeneratedMediaType(null))
        assert(!AiCoverPlanning.supportsGeneratedMediaType("image/svg+xml"))
    }

    @Test
    fun `coverFileExtension maps media types with a png default`() {
        assertEquals("jpg", AiCoverPlanning.coverFileExtension("image/jpeg"))
        assertEquals("webp", AiCoverPlanning.coverFileExtension("Image/WebP; charset=binary"))
        assertEquals("png", AiCoverPlanning.coverFileExtension("image/png"))
        assertEquals("png", AiCoverPlanning.coverFileExtension(null))
        assertEquals("png", AiCoverPlanning.coverFileExtension("application/octet-stream"))
    }
}
