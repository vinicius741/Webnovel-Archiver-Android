package com.vinicius741.webnovelarchiver.narration

import com.google.gson.annotations.SerializedName
import java.io.File

// Alternate names read narration JSON written by the minified 1.0.19 release. New writes use
// stable field names; keep these aliases so existing chapter audio and queued work survive upgrades.

/**
 * The voice a chapter is generated with, resolved when it is queued: [engine] is a concrete engine
 * (the built-in Kokoro id or a TTS app package, never "the phone's engine"), so changing settings
 * later never changes queued work. [voiceId] null means the engine's own default voice.
 */
data class NarrationVoice(
    @SerializedName(value = "engine", alternate = ["a"])
    val engine: String = "",
    @SerializedName(value = "voiceId", alternate = ["b"])
    val voiceId: String? = null,
    @SerializedName(value = "pitch", alternate = ["c"])
    val pitch: Float = 1f,
    @SerializedName(value = "label", alternate = ["d"])
    val label: String = "",
)

enum class NarrationOrigin {
    /** The user asked for this chapter. */
    MANUAL,

    /** Queued to stay ahead of the listener. Never changes a job that already exists. */
    KEEP_AHEAD,

    /** The user asked for new audio even though some exists (another voice, or a fresh take). */
    REGENERATE,
}

enum class NarrationJobState {
    QUEUED,
    FAILED,
}

/** One chapter to generate. Finished jobs are removed; the running one is the first queued job. */
data class NarrationJob(
    @SerializedName(value = "id", alternate = ["a"])
    val id: String = "",
    @SerializedName(value = "storyId", alternate = ["b"])
    val storyId: String = "",
    @SerializedName(value = "chapterId", alternate = ["c"])
    val chapterId: String = "",
    @SerializedName(value = "voice", alternate = ["d"])
    val voice: NarrationVoice = NarrationVoice(),
    @SerializedName(value = "origin", alternate = ["e"])
    val origin: NarrationOrigin = NarrationOrigin.MANUAL,
    @SerializedName(value = "state", alternate = ["f"])
    val state: NarrationJobState = NarrationJobState.QUEUED,
    @SerializedName(value = "error", alternate = ["g"])
    val error: String? = null,
    /** The version folder this job is building, so a restarted process resumes it. */
    @SerializedName(value = "version", alternate = ["h"])
    val version: String? = null,
    @SerializedName(value = "enqueuedAt", alternate = ["i"])
    val enqueuedAt: Long = 0L,
)

/** Everything `queue.json` holds. */
data class NarrationQueueDocument(
    @SerializedName(value = "jobs", alternate = ["a"])
    val jobs: List<NarrationJob> = emptyList(),
    /** The user paused generation, or it paused itself (out of storage). */
    @SerializedName(value = "paused", alternate = ["b"])
    val paused: Boolean = false,
    @SerializedName(value = "pauseReason", alternate = ["c"])
    val pauseReason: String? = null,
    /** Chapters to keep generated ahead of the one playing; 0 turns it off. */
    @SerializedName(value = "keepAhead", alternate = ["d"])
    val keepAhead: Int = NarrationPlanning.DEFAULT_KEEP_AHEAD,
    /** Measured audio milliseconds per character of text, per engine, for estimates. */
    @SerializedName(value = "msPerChar", alternate = ["e"])
    val msPerChar: Map<String, Double> = emptyMap(),
    /** Measured audio milliseconds produced per real millisecond, per engine, for estimates. */
    @SerializedName(value = "speed", alternate = ["f"])
    val speed: Map<String, Double> = emptyMap(),
    /** The one-time offer to allow generating with the screen off has been shown. */
    @SerializedName(value = "screenOffPromptShown", alternate = ["g"])
    val screenOffPromptShown: Boolean = false,
)

/**
 * What a pack version is generated from. It never changes after the version folder is created, so
 * clip `NNNN.pcm` in that folder always holds sentence NNNN of [sentences] in [voice].
 */
data class NarrationRecipe(
    @SerializedName(value = "storyId", alternate = ["a"])
    val storyId: String = "",
    @SerializedName(value = "chapterId", alternate = ["b"])
    val chapterId: String = "",
    @SerializedName(value = "chunkingVersion", alternate = ["c"])
    val chunkingVersion: Int = 0,
    @SerializedName(value = "textFingerprint", alternate = ["d"])
    val textFingerprint: String = "",
    /** The spoken chapter title, or null when the text already opens with it. */
    @SerializedName(value = "title", alternate = ["e"])
    val title: String? = null,
    @SerializedName(value = "sentences", alternate = ["f"])
    val sentences: List<String> = emptyList(),
    @SerializedName(value = "voice", alternate = ["g"])
    val voice: NarrationVoice = NarrationVoice(),
    /** What the audio depends on besides its text; clips are reused only between equal keys. */
    @SerializedName(value = "voiceKey", alternate = ["h"])
    val voiceKey: String = "",
)

/** Written last into a version folder: its presence means every clip is there. */
data class NarrationPackIndex(
    val format: Int = 1,
    @SerializedName(value = "codec", alternate = ["a"])
    val codec: String = NarrationStore.CODEC_PCM,
    @SerializedName(value = "sampleRate", alternate = ["b"])
    val sampleRate: Int = 0,
    @SerializedName(value = "sentenceDurationsMs", alternate = ["c"])
    val sentenceDurationsMs: List<Long> = emptyList(),
    @SerializedName(value = "titleDurationMs", alternate = ["d"])
    val titleDurationMs: Long? = null,
    @SerializedName(value = "totalDurationMs", alternate = ["e"])
    val totalDurationMs: Long = 0L,
    @SerializedName(value = "bytes", alternate = ["f"])
    val bytes: Long = 0L,
    @SerializedName(value = "generatedAt", alternate = ["g"])
    val generatedAt: Long = 0L,
)

/** A complete, published chapter pack. */
data class NarrationPack(
    val storyId: String,
    val chapterId: String,
    val version: String,
    val dir: File,
    val recipe: NarrationRecipe,
    val index: NarrationPackIndex,
)

/** A chapter's narration status, as the screens show it. */
sealed interface NarrationChapterStatus {
    data object None : NarrationChapterStatus

    data class Queued(
        val position: Int,
    ) : NarrationChapterStatus

    data class Generating(
        val progress: Float,
    ) : NarrationChapterStatus

    data class Failed(
        val reason: String,
    ) : NarrationChapterStatus

    data class Ready(
        val pack: NarrationPack,
    ) : NarrationChapterStatus

    /** A pack exists but the chapter's text has changed since. */
    data class Outdated(
        val pack: NarrationPack,
    ) : NarrationChapterStatus
}

/** The generation worker's live state. */
data class NarrationProgress(
    val job: NarrationJob? = null,
    val storyTitle: String = "",
    val chapterTitle: String = "",
    val done: Int = 0,
    val total: Int = 0,
    /** Estimated real time left for the whole queue, when known. */
    val etaMs: Long? = null,
    /** Estimated real time left for this chapter, when known. */
    val chapterEtaMs: Long? = null,
    /** Audio generated per second of real time in this chapter so far, once measurable. */
    val speed: Double? = null,
    /** Queued chapters waiting behind this one. */
    val queuedAfter: Int = 0,
    /** The chapter is about to start: its text is read and the voice is loading. */
    val preparing: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}
