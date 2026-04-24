package expo.modules.ttsmediasession

import android.util.Log
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class TtsMediaSessionModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("TtsMediaSession")

    Events("onMediaButton", "onPlaybackState")

    OnCreate {
      TtsMediaSessionEventEmitter.register(this@TtsMediaSessionModule)
    }

    AsyncFunction("startPlayback") {
      units: List<String>,
      title: String,
      storyId: String?,
      chapterId: String?,
      startIndex: Int,
      pitch: Double,
      rate: Double,
      voiceIdentifier: String? ->
      val context = appContext.reactContext
      if (context == null) {
        Log.w(TAG, "startPlayback called with null reactContext")
        return@AsyncFunction null
      }
      runCatching {
        TtsMediaSessionService.startPlayback(
          context = context,
          units = ArrayList(units),
          title = title,
          storyId = storyId,
          chapterId = chapterId,
          startIndex = startIndex,
          pitch = pitch.toFloat(),
          rate = rate.toFloat(),
          voiceIdentifier = voiceIdentifier,
        )
      }.onFailure { error ->
        Log.e(TAG, "Failed to start native TTS playback", error)
      }
      null
    }

    AsyncFunction("pausePlayback") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.PAUSE")
      null
    }

    AsyncFunction("resumePlayback") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.RESUME")
      null
    }

    AsyncFunction("playPause") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.PLAY_PAUSE")
      null
    }

    AsyncFunction("next") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.NEXT")
      null
    }

    AsyncFunction("previous") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.PREVIOUS")
      null
    }

    AsyncFunction("seekToUnit") { index: Int ->
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.seekToUnit(context, index)
      null
    }

    AsyncFunction("stopPlayback") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.command(context, "expo.modules.ttsmediasession.action.STOP")
      null
    }

    AsyncFunction("getVoices") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.resolve(emptyList<Map<String, String>>())
        return@AsyncFunction
      }
      TtsMediaSessionService.getVoices(context) { voices ->
        promise.resolve(
          voices.map {
            mapOf(
              "identifier" to it.name,
              "name" to it.name,
              "language" to it.locale.toLanguageTag(),
              "quality" to it.quality.toString(),
              "latency" to it.latency.toString(),
            )
          }
        )
      }
    }
  }

  companion object {
    private const val TAG = "TtsMediaSessionModule"
  }
}
