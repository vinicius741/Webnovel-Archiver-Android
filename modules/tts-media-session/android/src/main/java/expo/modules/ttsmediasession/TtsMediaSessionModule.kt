package expo.modules.ttsmediasession

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class TtsMediaSessionModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("TtsMediaSession")

    Events("onMediaButton")

    OnCreate {
      TtsMediaSessionEventEmitter.register(this@TtsMediaSessionModule)
    }

    AsyncFunction("startSession") { title: String, body: String, isPlaying: Boolean ->
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.start(context, title, body, isPlaying)
      null
    }

    AsyncFunction("updateSession") { title: String, body: String, isPlaying: Boolean ->
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.update(context, title, body, isPlaying)
      null
    }

    AsyncFunction("stopSession") {
      val context = appContext.reactContext ?: return@AsyncFunction null
      TtsMediaSessionService.stop(context)
      null
    }
  }
}
