package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** Lists the speech engines installed on the device and the voices each one offers. */
internal object TtsEngineCatalog {
    /** Neural engines load their model before they answer, which can take a while. */
    private const val VOICE_LOAD_TIMEOUT_MS = 30_000L

    fun installed(context: Context): List<TtsEngineInfo> {
        val pm = context.packageManager
        val services =
            runCatching { queryEngines(pm) }
                .onFailure { Timber.w(it, "TTS engine query failed") }
                .getOrDefault(emptyList())
        return TtsEnginePlanning.sorted(
            services.mapNotNull { it.serviceInfo }.map { TtsEngineInfo(it.packageName, it.loadLabel(pm).toString()) },
        )
    }

    fun installedPackages(context: Context): Set<String> = installed(context).mapTo(mutableSetOf()) { it.packageName }

    /** The engine chosen in the phone's text-to-speech settings, when Android says. */
    fun phoneDefault(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** The voices [enginePackage] offers (null: the phone's engine); null if it never started. */
    suspend fun voices(
        context: Context,
        enginePackage: String?,
    ): List<VoiceInfo>? =
        withTimeoutOrNull(VOICE_LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var tts: TextToSpeech? = null
                val listener =
                    TextToSpeech.OnInitListener { status ->
                        val voices =
                            if (status == TextToSpeech.SUCCESS) {
                                runCatching { TtsVoicePlanning.toVoiceInfo(tts?.voices) }.getOrNull()
                            } else {
                                null
                            }
                        tts?.shutdown()
                        if (continuation.isActive) continuation.resume(voices)
                    }
                tts = TextToSpeech(context.applicationContext, listener, enginePackage)
                continuation.invokeOnCancellation { tts?.shutdown() }
            }
        }

    private fun queryEngines(pm: PackageManager): List<ResolveInfo> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
    }
}
