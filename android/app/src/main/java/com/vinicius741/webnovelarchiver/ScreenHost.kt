package com.vinicius741.webnovelarchiver

import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.vinicius741.webnovelarchiver.core.AppStorage
import com.vinicius741.webnovelarchiver.core.DownloadEngine
import com.vinicius741.webnovelarchiver.core.EpubEngine
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StorySyncEngine
import com.vinicius741.webnovelarchiver.core.TtsEngine
import kotlinx.coroutines.CoroutineScope

/**
 * The contract between [MainActivity] and the screen/action extension functions split across
 * the `screens/`, `actions/`, and `ui/` files. Exposes only the shared dependencies and the
 * root view — everything else (navigation, business actions, the view DSL, shared helpers)
 * lives as `internal` extension functions on this type (or on `ViewGroup`/`AppCompatActivity`).
 *
 * Screens reference each other freely: inside any `internal fun ScreenHost.showXxx()` body,
 * `this` is a `ScreenHost`, and Kotlin's implicit-receiver chain keeps it visible inside the
 * nested `LinearLayout.() -> Unit` screen blocks, so unqualified calls like `showDetails(id)`
 * resolve exactly as they did when everything was a member of `MainActivity`.
 */
interface ScreenHost {
    val app: AppCompatActivity
    val scope: CoroutineScope
    val storage: AppStorage
    val syncEngine: StorySyncEngine
    val downloadEngine: DownloadEngine
    val epubEngine: EpubEngine
    val ttsEngine: TtsEngine
    var activeStory: Story?
    val frame: FrameLayout
    val importBackupLauncher: ActivityResultLauncher<Array<String>>
    val importFullBackupLauncher: ActivityResultLauncher<Array<String>>
}
