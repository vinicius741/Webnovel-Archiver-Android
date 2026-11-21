package com.vinicius741.webnovelarchiver.source.network

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuardedJavascriptDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anAnswerArrivesOnceAndAScriptThatNeverReturnsIsReportedLost() {
        val results = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(2)
        var web: WebView? = null
        instrumentation.runOnMainSync {
            val created = WebView(instrumentation.targetContext)
            created.settings.javaScriptEnabled = true
            web = created
            val javascript = GuardedJavascript(Handler(Looper.getMainLooper()), timeoutMillis = 500)
            val record = { result: String ->
                results += result
                finished.countDown()
            }
            javascript.evaluate(created, "1+1", onLost = { record("first lost") }) { record("first=$it") }
            // The renderer is busy forever, so this script's callback can never be delivered.
            javascript.evaluate(created, "while(true){}", onLost = { record("second lost") }) { record("second=$it") }
        }

        assertTrue("both scripts should settle", finished.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { web?.let(WebViewSafety::destroy) }
        assertEquals(listOf("first=2", "second lost"), results.toList())
    }
}
