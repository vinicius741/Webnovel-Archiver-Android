package expo.modules.ttsmediasession

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference

object TtsMediaSessionEventEmitter {
  private var moduleRef: WeakReference<TtsMediaSessionModule>? = null
  private val handler = Handler(Looper.getMainLooper())

  fun register(module: TtsMediaSessionModule) {
    moduleRef = WeakReference(module)
  }

  fun emitPlayPause() {
    val module = moduleRef?.get() ?: return
    val payload = Bundle().apply {
      putString("action", "playPause")
    }

    handler.post {
      try {
        module.sendEvent("onMediaButton", payload)
      } catch (e: Exception) {
        Log.w("TtsMediaSession", "Failed to emit media button event: ${e.message}")
      }
    }
  }
}
