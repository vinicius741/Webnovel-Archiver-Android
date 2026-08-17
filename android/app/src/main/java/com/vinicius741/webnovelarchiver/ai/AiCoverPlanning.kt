package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Pure planning for AI-generated cover art. Builds the two-stage request: the chat messages that
 * ask the description model to write an image-generation prompt from the novel's material, and the
 * parameter set for the image call itself (gated on what the selected model supports). All
 * unit-testable without Android or network, mirroring [AiDescriptionPlanning].
 */
object AiCoverPlanning {
    /**
     * Output budget for the image prompt: 60–120 words plus preambles. Generous on purpose — a
     * tight budget makes reasoning-style models occasionally spend it all before writing any
     * text, returning an empty completion (the engine also retries that flake once).
     */
    const val MAX_OUTPUT_TOKENS = 1_000

    /** Hard cap on the cleaned prompt sent to the image model. */
    internal const val MAX_PROMPT_CHARS = 1_500

    /** Portrait ratio matching the app's cover cards (80×120dp and 150×225dp). */
    const val ASPECT_RATIO = "2:3"

    /** 1K is ample for card display and full-screen zoom; higher tiers only cost more. */
    const val RESOLUTION = "1K"

    const val QUALITY = "medium"

    /**
     * Chat messages for the prompt-writing call. Sends everything known about the novel — title,
     * author, tags, the description currently displayed (AI synopsis when active, else source),
     * and the same capped opening-chapter context the description generator uses — so the model
     * can ground the cover in concrete story imagery.
     */
    fun buildPromptMessages(
        story: Story,
        chapters: List<AiDescriptionPlanning.ChapterText>,
    ): List<OpenRouterMessage> {
        val metadata =
            buildList {
                add("Title: ${story.title}")
                if (story.author.isNotBlank()) add("Author: ${story.author}")
                story.tags?.takeIf { it.isNotEmpty() }?.let { add("Tags: ${it.joinToString(", ")}") }
                AiDescriptionPlanning.activeDescription(story)?.let { add("Description: $it") }
            }.joinToString("\n")
        val chapterBlock =
            AiDescriptionPlanning.enforceTotalContextCap(chapters).joinToString("\n\n") { chapter ->
                buildString {
                    append("Chapter ${chapter.number}")
                    if (chapter.title.isNotBlank()) append(": ${chapter.title}")
                    append("\n")
                    append(chapter.text)
                }
            }
        val userContent =
            "Here is the material for a web novel's cover art:\n\n$metadata\n\n$chapterBlock"
        return listOf(
            OpenRouterMessage(role = "system", content = SYSTEM_PROMPT),
            OpenRouterMessage(role = "user", content = userContent),
        )
    }

    /**
     * Post-processes the model's reply into a single flowing paragraph: trims, drops wrapping
     * quotes some models add, collapses all whitespace runs to single spaces, and caps the length.
     * Returns null when nothing presentable remains.
     */
    fun cleanGeneratedPrompt(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("\"")) text = text.removePrefix("\"")
        if (text.endsWith("\"")) text = text.removeSuffix("\"")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.length > MAX_PROMPT_CHARS) text = text.take(MAX_PROMPT_CHARS).trim()
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Whether the app should display the locally generated cover rather than the source one,
     * mirroring [AiDescriptionPlanning.isAiDescriptionActive]: the toggle decides while both
     * covers exist, and the AI cover stays active on its own when the source has none.
     */
    fun isAiCoverActive(story: Story): Boolean {
        val hasAiCover = !story.aiCoverPath.isNullOrBlank()
        val hasSourceCover = !story.coverUrl.isNullOrBlank()
        return hasAiCover && (story.showAiCover || !hasSourceCover)
    }

    /**
     * The optional parameters for the image call. `supportedParameters` is the selected model's
     * entry from the image catalog (`GET /api/v1/images/models`); each optional parameter is sent
     * only when the model lists it, and — when the catalog also enumerates the parameter's allowed
     * values — only with a value that enum accepts: several models take a parameter the app wants
     * but with a narrower value set (recraft offers `3:4` but not `2:3`; seedream-lite starts at
     * `2K`), and an out-of-enum value fails the whole call with HTTP 400 after the prompt-writing
     * stage has already been billed. Null (catalog unavailable) means a minimal `model` + `prompt`
     * request — the safest shape for any model.
     */
    fun buildImageRequestParams(supportedParameters: Map<String, List<String>?>?): ImageRequestParams =
        ImageRequestParams(
            aspectRatio = supportedParameters.preferredValue("aspect_ratio", ASPECT_RATIO, ASPECT_RATIO_FALLBACKS),
            resolution = supportedParameters.preferredValue("resolution", RESOLUTION, RESOLUTION_FALLBACKS),
            quality = supportedParameters.preferredValue("quality", QUALITY, QUALITY_FALLBACKS),
        )

    /**
     * The value to send for one optional parameter: the app default when the model accepts it (or
     * when its accepted values are unknown), the first preferred stand-in from [fallbacks] the
     * model does accept when it does not, and null — send nothing — when the model lists no
     * acceptable value (its own default then applies).
     */
    private fun Map<String, List<String>?>?.preferredValue(
        parameter: String,
        default: String,
        fallbacks: List<String>,
    ): String? {
        // A missing key means the parameter is unsupported (omit); a present-but-null value means
        // supported with unknown constraints (send the default). The two must not be conflated.
        if (this == null || !containsKey(parameter)) return null
        val allowed = get(parameter)
        if (allowed == null || default in allowed) return default
        return fallbacks.firstOrNull { it in allowed }
    }

    /** Portrait-first aspect-ratio stand-ins for enums without [ASPECT_RATIO], then the model's own default. */
    private val ASPECT_RATIO_FALLBACKS = listOf("3:4", "9:16", "1:2", "auto")

    /** Resolution stand-ins, cheapest first, for enums without [RESOLUTION]. */
    private val RESOLUTION_FALLBACKS = listOf("2K", "4K")

    /** Quality stand-ins for enums that use different tier names than [QUALITY]. */
    private val QUALITY_FALLBACKS = listOf("standard", "low", "auto")

    /** File extension for a generated cover, derived from the API's `media_type`. */
    fun coverFileExtension(mediaType: String?): String =
        when (mediaType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }

    /** Optional image-request parameters; every field null = send only model + prompt. */
    data class ImageRequestParams(
        val aspectRatio: String?,
        val resolution: String?,
        val quality: String?,
    )

    private const val SYSTEM_PROMPT =
        "You write prompts for AI image generators. Using only the web novel material provided " +
            "(title, author, tags, description, and opening chapters), write ONE prompt for that " +
            "novel's cover art as a single paragraph of 60 to 120 words. Describe the artwork " +
            "itself — the scene, subject, and setting — never \"a book\", \"a cover\", or a mockup: " +
            "the generated image IS the cover. Build it in layers: the main subject with concrete " +
            "visual traits drawn from the material (appearance, clothing, expression), the setting " +
            "or signature imagery, an art style and medium suited to the genre (for example anime " +
            "key visual, digital oil painting, cinematic 3D render), a composition for a tall " +
            "portrait book cover with one clear focal point, the lighting, and the color palette " +
            "and mood. Prefer concrete nouns and specific visual detail over vague words like " +
            "\"beautiful\" or \"epic\". Title text: you decide. If the novel's title is short and " +
            "would render cleanly, you may include it as minimal, elegantly typeset cover " +
            "lettering; if it is long or awkward, shorten it to its most evocative fragment or " +
            "omit text entirely. Never invent text that is not the title, and include no other " +
            "text, no watermark, no border, and no frame. Output only the prompt itself: no " +
            "preamble, no quotation marks, no headings, no explanation."
}
