package com.vinicius741.webnovelarchiver.feature.browser

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CloudflareSolveActivityDeviceTest {
    @Test
    fun solverContentClearsSystemBars() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                CloudflareSolveActivity::class.java,
            ).apply {
                putExtra("cloudflare_solve_url", "https://example.invalid/cloudflare-test")
            }

        ActivityScenario.launch<CloudflareSolveActivity>(intent).use { scenario ->
            var insetsChecked = false
            val deadline = System.currentTimeMillis() + 5_000
            while (!insetsChecked && System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val toolbar = activity.window.decorView.findToolbar() ?: return@onActivity
                    val insets = ViewCompat.getRootWindowInsets(toolbar) ?: return@onActivity
                    val safeInsets =
                        insets.getInsets(
                            WindowInsetsCompat.Type.systemBars() or
                                WindowInsetsCompat.Type.displayCutout(),
                        )
                    if (safeInsets.top == 0) return@onActivity

                    assertEquals(safeInsets.top, toolbar.paddingTop)
                    assertEquals(safeInsets.bottom, (toolbar.parent as View).paddingBottom)
                    insetsChecked = true
                }
                if (!insetsChecked) Thread.sleep(50)
            }
            assertTrue("System-bar insets were not dispatched", insetsChecked)
        }
    }
}

private fun View.findToolbar(): Toolbar? {
    if (this is Toolbar) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findToolbar()?.let { return it }
        }
    }
    return null
}
