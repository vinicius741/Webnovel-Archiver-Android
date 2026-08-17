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
        assert(user.contains("Title: Wandering Blades"))
        assert(user.contains("Author: A. Author"))
        assert(user.contains("Tags: cultivation, comedy"))
        assert(user.contains("Description: source synopsis"))
        assert(user.contains("Chapter 1: Arrival"))
        assert(user.contains("Chapter 2\n"))
        assert(user.contains("He arrived at the sect."))
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

        assert(user.contains("Description: ai synopsis"))
        assert(!user.contains("source synopsis"))
    }

    @Test
    fun `buildPromptMessages omits absent metadata lines`() {
        val story = Story(id = "s1", title = "T")

        val user = AiCoverPlanning.buildPromptMessages(story, emptyList())[1].content

        assert(user.contains("Title: T"))
        assert(!user.contains("Author:"))
        assert(!user.contains("Tags:"))
        assert(!user.contains("Description:"))
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
    }

    @Test
    fun `buildImageRequestParams drops options the model does not support`() {
        val params = AiCoverPlanning.buildImageRequestParams(mapOf("resolution" to listOf("1K", "2K")))

        assertNull(params.aspectRatio)
        assertEquals("1K", params.resolution)
        assertNull(params.quality)
    }

    @Test
    fun `buildImageRequestParams sends a minimal request when the catalog is unavailable`() {
        val params = AiCoverPlanning.buildImageRequestParams(null)

        assertNull(params.aspectRatio)
        assertNull(params.resolution)
        assertNull(params.quality)
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
    fun `coverFileExtension maps media types with a png default`() {
        assertEquals("jpg", AiCoverPlanning.coverFileExtension("image/jpeg"))
        assertEquals("webp", AiCoverPlanning.coverFileExtension("Image/WebP; charset=binary"))
        assertEquals("png", AiCoverPlanning.coverFileExtension("image/png"))
        assertEquals("png", AiCoverPlanning.coverFileExtension(null))
        assertEquals("png", AiCoverPlanning.coverFileExtension("application/octet-stream"))
    }
}
