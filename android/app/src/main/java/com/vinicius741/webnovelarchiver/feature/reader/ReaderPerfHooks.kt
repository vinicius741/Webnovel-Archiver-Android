package com.vinicius741.webnovelarchiver.feature.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation

// Debug perf-session hooks split out of ReaderScreen so the screen file stays within its
// line budget; every function compiles out or no-ops in release.

internal fun ScreenHost.recordReaderPreparingForPerf(
    storyId: String,
    chapterId: String,
) {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPreparing(storyId, chapterId)
}

internal suspend fun ScreenHost.prepareReaderDocumentWithPerf(
    storyId: String,
    chapterId: String,
    palette: ReaderDocumentPalette,
): ReaderPreparation {
    val startedNanos = if (BuildConfig.DEBUG) System.nanoTime() else 0L
    val preparation = ReaderDocumentPreparer(repository).prepare(storyId, chapterId, palette)
    if (BuildConfig.DEBUG) {
        val outcome =
            when (preparation) {
                is ReaderPreparation.Ready -> "ready"
                ReaderPreparation.Missing -> "missing"
                is ReaderPreparation.Failed -> "failed"
            }
        PerfInstrumentation.recordReaderPrepareDone(outcome, (System.nanoTime() - startedNanos) / 1_000_000)
    }
    return preparation
}

/** Release never sets a WebViewClient; debug sessions mark onPageFinished as content painted. */
internal fun attachReaderPaintTracking(reader: WebView) {
    if (!BuildConfig.DEBUG) return
    reader.webViewClient =
        object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?,
            ) {
                PerfInstrumentation.recordReaderPainted()
            }
        }
}

internal fun recordReaderPresentedForPerf() {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPresented()
}
