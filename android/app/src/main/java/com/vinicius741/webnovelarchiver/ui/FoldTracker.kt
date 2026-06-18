package com.vinicius741.webnovelarchiver.ui

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Wraps Jetpack WindowManager ([WindowInfoTracker]) for the single-activity app. Exposes whether the
 * current window has a folding feature (a hinge/inner display), so [resolveScreenLayout] can promote
 * a sub-600dp inner Fold screen to a tablet-style layout — the native equivalent of the legacy
 * `@logicwind/react-native-fold-detection` module.
 *
 * The presence of *any* [FoldingFeature] in the [WindowLayoutInfo] is enough; the planning engine
 * only consults the boolean (matching the RN `hasFoldingFeature`), not the hinge geometry.
 *
 * Constructed once in [com.vinicius741.webnovelarchiver.MainActivity] and re-queried on every screen
 * render via [com.vinicius741.webnovelarchiver.ui.currentScreenLayout].
 */
class FoldTracker(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(false)
    val isFoldingFeature: StateFlow<Boolean> = mutable.asStateFlow()

    init {
        scope.launch {
            runCatching {
                WindowInfoTracker
                    .getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collectLatest(::onLayoutInfo)
            }
        }
    }

    private fun onLayoutInfo(info: WindowLayoutInfo) {
        mutable.value = info.displayFeatures.any { it is FoldingFeature }
    }
}
