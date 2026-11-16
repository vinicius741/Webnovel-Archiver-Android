package com.vinicius741.webnovelarchiver.tts.kokoro

import android.speech.tts.Voice
import com.vinicius741.webnovelarchiver.tts.VoiceInfo

/**
 * The built-in engines and their voices (see [BuiltInModel]). An engine is stored in
 * `TtsSettings.enginePackage` under its [BuiltInModel.engineId], beside real TTS app packages, and a
 * voice under its [identifier]: the model version plus the voice's code, so another model cannot
 * pick the wrong speaker. The names without a model are the original Kokoro v0.19 engine's.
 */
internal object KokoroVoices {
    val ENGINE_ID: String = BuiltInModel.KOKORO_V019.engineId
    val ENGINE_LABEL: String = BuiltInModel.KOKORO_V019.engineLabel

    /** Kokoro v0.19's speakers, in its voices.bin order. */
    val all: List<KokoroVoice> get() = BuiltInModel.KOKORO_V019.voices

    /** The voice used when none is chosen. */
    val default: KokoroVoice get() = BuiltInModel.KOKORO_V019.defaultVoice

    /** Whether [enginePackage] is one of the built-in engines (any [BuiltInModel]). */
    fun isEngine(enginePackage: String?): Boolean = BuiltInModel.forEngine(enginePackage) != null

    fun model(enginePackage: String?): BuiltInModel? = BuiltInModel.forEngine(enginePackage)

    /** Kokoro v0.19's key for [speakerId]; see [BuiltInModel.voiceKey]. */
    fun voiceKey(speakerId: Int): String = BuiltInModel.KOKORO_V019.voiceKey(all.first { it.speakerId == speakerId })

    fun voiceKey(voice: KokoroVoice): String = voice.model.voiceKey(voice)

    fun identifier(voice: KokoroVoice): String = voice.model.idPrefix + voice.code

    fun isKokoroIdentifier(identifier: String?): Boolean = BuiltInModel.entries.any { identifier?.startsWith(it.idPrefix) == true }

    fun forIdentifier(identifier: String?): KokoroVoice? = BuiltInModel.forIdentifier(identifier)

    /** [identifier]'s voice when it belongs to [model], else the model's default. */
    fun voiceFor(
        model: BuiltInModel,
        identifier: String?,
    ): KokoroVoice = forIdentifier(identifier)?.takeIf { it.model == model } ?: model.defaultVoice

    fun voiceInfos(model: BuiltInModel = BuiltInModel.KOKORO_V019): List<VoiceInfo> =
        model.voices.map {
            VoiceInfo(
                identifier = identifier(it),
                name = it.name,
                language = it.language,
                quality = Voice.QUALITY_VERY_HIGH,
                latency = Voice.LATENCY_NORMAL,
            )
        }
}
