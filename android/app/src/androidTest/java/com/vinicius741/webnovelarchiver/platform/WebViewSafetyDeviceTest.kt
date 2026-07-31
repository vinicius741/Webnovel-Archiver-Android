package com.vinicius741.webnovelarchiver.platform

import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WebViewSafetyDeviceTest {
    @Test
    fun disposeAllDestroysWebViewsWithoutSkippingShiftedSiblings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val sibling = TextView(context)
            val root =
                LinearLayout(context).apply {
                    addView(WebView(context))
                    addView(sibling)
                    addView(WebView(context))
                }

            WebViewSafety.disposeAll(root)

            assertEquals(1, root.childCount)
            assertSame(sibling, root.getChildAt(0))
        }
    }
}
