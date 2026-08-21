package com.vinicius741.webnovelarchiver.app

import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import timber.log.Timber

// Startup chrome shown on the root frame while (or after) repository hydration runs.

internal fun MainActivity.showStartupLoading() {
    frame.removeAllViews()
    frame.addView(
        ProgressBar(this),
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        ),
    )
}

internal fun MainActivity.showStartupFailure(error: Throwable) {
    Timber.e(error, "Repository startup failed")
    frame.removeAllViews()
    frame.addView(
        TextView(this).apply {
            text = "The library could not be loaded. Restart the app to try again."
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        },
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
}
