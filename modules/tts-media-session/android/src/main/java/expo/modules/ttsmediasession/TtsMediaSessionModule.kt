package expo.modules.ttsmediasession

import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class TtsMediaSessionModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("TtsMediaSession")

    Events("onMediaButton")

    OnCreate {
      TtsMediaSessionEventEmitter.register(this@TtsMediaSessionModule)
    }

    AsyncFunction("startSession") { title: String, body: String, isPlaying: Boolean, storyId: String?, chapterId: String? ->
      val context = appContext.reactContext
      if (context == null) {
        Log.w(TAG, "startSession called with null reactContext")
        return@AsyncFunction null
      }
      runCatching {
        TtsMediaSessionService.start(context, title, body, isPlaying, storyId, chapterId)
      }.onFailure { error ->
        Log.e(TAG, "Failed to start media session", error)
      }
      null
    }

    AsyncFunction("updateSession") { title: String, body: String, isPlaying: Boolean, storyId: String?, chapterId: String? ->
      val context = appContext.reactContext
      if (context == null) {
        Log.w(TAG, "updateSession called with null reactContext")
        return@AsyncFunction null
      }
      runCatching {
        TtsMediaSessionService.update(context, title, body, isPlaying, storyId, chapterId)
      }.onFailure { error ->
        Log.e(TAG, "Failed to update media session", error)
      }
      null
    }

    AsyncFunction("stopSession") {
      val context = appContext.reactContext
      if (context == null) {
        Log.w(TAG, "stopSession called with null reactContext")
        return@AsyncFunction null
      }
      runCatching {
        TtsMediaSessionService.stop(context)
      }.onFailure { error ->
        Log.e(TAG, "Failed to stop media session", error)
      }
      null
    }
  }

  companion object {
    private const val TAG = "TtsMediaSessionModule"
  }
}
