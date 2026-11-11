package com.vinicius741.webnovelarchiver.tts.kokoro

import android.speech.tts.Voice
import com.vinicius741.webnovelarchiver.tts.VoiceInfo

/** One of the Kokoro model's speakers; [speakerId] is its index in the model's voices file. */
internal data class KokoroVoice(
    val speakerId: Int,
    val code: String,
    val name: String,
    val language: String,
)

/**
 * The built-in Kokoro engine and its voices. The engine is stored in `TtsSettings.enginePackage`
 * under [ENGINE_ID], beside real TTS app packages, and a voice under its [identifier]: the model
 * version plus the voice's code, so a later model with other speakers cannot pick the wrong one.
 */
internal object KokoroVoices {
    const val ENGINE_ID = "builtin:kokoro-en-v0_19"
    const val ENGINE_LABEL = "Kokoro (built in)"
    private const val ID_PREFIX = "kokoro-en-v0_19:"
    private const val US = "en-US"
    private const val UK = "en-GB"

    /** Speaker order of kokoro-en-v0_19's voices.bin, as documented by sherpa-onnx. */
    val all: List<KokoroVoice> =
        listOf(
            KokoroVoice(0, "af", "Default (American, female)", US),
            KokoroVoice(1, "af_bella", "Bella (American, female)", US),
            KokoroVoice(2, "af_nicole", "Nicole (American, female)", US),
            KokoroVoice(3, "af_sarah", "Sarah (American, female)", US),
            KokoroVoice(4, "af_sky", "Sky (American, female)", US),
            KokoroVoice(5, "am_adam", "Adam (American, male)", US),
            KokoroVoice(6, "am_michael", "Michael (American, male)", US),
            KokoroVoice(7, "bf_emma", "Emma (British, female)", UK),
            KokoroVoice(8, "bf_isabella", "Isabella (British, female)", UK),
            KokoroVoice(9, "bm_george", "George (British, male)", UK),
            KokoroVoice(10, "bm_lewis", "Lewis (British, male)", UK),
        )

    /** The voice used when none is chosen. */
    val default: KokoroVoice get() = all.first()

    fun isEngine(enginePackage: String?): Boolean = enginePackage == ENGINE_ID

    fun identifier(voice: KokoroVoice): String = ID_PREFIX + voice.code

    fun isKokoroIdentifier(identifier: String?): Boolean = identifier?.startsWith(ID_PREFIX) == true

    fun forIdentifier(identifier: String?): KokoroVoice? = all.firstOrNull { identifier(it) == identifier }

    fun voiceInfos(): List<VoiceInfo> =
        all.map {
            VoiceInfo(
                identifier = identifier(it),
                name = it.name,
                language = it.language,
                quality = Voice.QUALITY_VERY_HIGH,
                latency = Voice.LATENCY_NORMAL,
            )
        }
}
