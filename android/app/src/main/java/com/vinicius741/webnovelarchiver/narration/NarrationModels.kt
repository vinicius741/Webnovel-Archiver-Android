package com.vinicius741.webnovelarchiver.narration

import java.io.File

/**
 * The voice a chapter is generated with, resolved when it is queued: [engine] is a concrete engine
 * (the built-in Kokoro id or a TTS app package, never "the phone's engine"), so changing settings
 * later never changes queued work. [voiceId] null means the engine's own default voice.
 */
data class NarrationVoice(
    val engine: String = "",
    val voiceId: String? = null,
    val pitch: Float = 1f,
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
    val id: String = "",
    val storyId: String = "",
    val chapterId: String = "",
    val voice: NarrationVoice = NarrationVoice(),
    val origin: NarrationOrigin = NarrationOrigin.MANUAL,
    val state: NarrationJobState = NarrationJobState.QUEUED,
    val error: String? = null,
    /** The version folder this job is building, so a restarted process resumes it. */
    val version: String? = null,
    val enqueuedAt: Long = 0L,
)

/** Everything `queue.json` holds. */
data class NarrationQueueDocument(
    val jobs: List<NarrationJob> = emptyList(),
    /** The user paused generation, or it paused itself (out of storage). */
    val paused: Boolean = false,
    val pauseReason: String? = null,
    /** Chapters to keep generated ahead of the one playing; 0 turns it off. */
    val keepAhead: Int = NarrationPlanning.DEFAULT_KEEP_AHEAD,
    /** Measured audio milliseconds per character of text, per engine, for estimates. */
    val msPerChar: Map<String, Double> = emptyMap(),
    /** Measured audio milliseconds produced per real millisecond, per engine, for estimates. */
    val speed: Map<String, Double> = emptyMap(),
    /** The one-time offer to allow generating with the screen off has been shown. */
    val screenOffPromptShown: Boolean = false,
    /** The user's computers that can generate Kokoro audio (see `docs/tts/remote-narration-servers.md`). */
    val servers: List<NarrationServer> = emptyList(),
    /** Id of the server Kokoro chapters generate on; null generates on this phone. */
    val generateOn: String? = null,
    /** Generation paused itself because that server could not be used; choosing a place resumes it. */
    val pausedForServer: Boolean = false,
) {
    /** The server Kokoro chapters generate on, or null for this phone. */
    val generateOnServer: NarrationServer? get() = generateOn?.let { id -> servers.firstOrNull { it.id == id } }
}

/**
 * A computer running `scripts/narration-server` that generates Kokoro audio for this phone. [address]
 * is its local-network address and [tailscaleAddress] its Tailscale one, each as the user typed it (a
 * host or IP, optionally with a port) and either may be blank. The app tries the local network first
 * and falls back to Tailscale. Stored only in the narration folder, which backups leave out, so the
 * token stays on this phone.
 */
data class NarrationServer(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val token: String = "",
    val tailscaleAddress: String = "",
)

/**
 * What a pack version is generated from. It never changes after the version folder is created, so
 * clip `NNNN.pcm` in that folder always holds sentence NNNN of [sentences] in [voice].
 */
data class NarrationRecipe(
    val storyId: String = "",
    val chapterId: String = "",
    val chunkingVersion: Int = 0,
    val textFingerprint: String = "",
    /** The spoken chapter title, or null when the text already opens with it. */
    val title: String? = null,
    val sentences: List<String> = emptyList(),
    val voice: NarrationVoice = NarrationVoice(),
    /** What the audio depends on besides its text; clips are reused only between equal keys. */
    val voiceKey: String = "",
)

/** Written last into a version folder: its presence means every clip is there. */
data class NarrationPackIndex(
    val format: Int = 1,
    val codec: String = NarrationStore.CODEC_PCM,
    val sampleRate: Int = 0,
    val sentenceDurationsMs: List<Long> = emptyList(),
    val titleDurationMs: Long? = null,
    val totalDurationMs: Long = 0L,
    val bytes: Long = 0L,
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
    /** The computer generating it, or null on this phone. */
    val generatingOn: String? = null,
    /** Why generation is waiting on that computer (it is low on memory), or null; it resumes by itself. */
    val waitingFor: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}
