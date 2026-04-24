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

  fun emitPlaybackState(
    status: String,
    title: String,
    currentIndex: Int,
    total: Int,
    isPlaying: Boolean,
    isPaused: Boolean,
    storyId: String?,
    chapterId: String?,
  ) {
    val module = moduleRef?.get() ?: return
    val payload = Bundle().apply {
      putString("status", status)
      putString("title", title)
      putInt("currentIndex", currentIndex)
      putInt("total", total)
      putBoolean("isPlaying", isPlaying)
      putBoolean("isPaused", isPaused)
      putString("storyId", storyId)
      putString("chapterId", chapterId)
    }

    handler.post {
      try {
        module.sendEvent("onPlaybackState", payload)
      } catch (e: Exception) {
        Log.w("TtsMediaSession", "Failed to emit playback state: ${e.message}")
      }
    }
  }
}
