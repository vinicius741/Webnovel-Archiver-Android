package com.vinicius741.webnovelarchiver.feature.browser

import android.app.AlertDialog
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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CloudflareSolveActivityDeviceTest {
    @Test
    fun doneWithoutClearanceOffersConfirmedRetryEscapeHatch() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                CloudflareSolveActivity::class.java,
            ).apply {
                putExtra("cloudflare_solve_url", "https://example.invalid/cloudflare-test")
            }
        var retried = false
        SourceAccessRetryCoordinator.arm { retried = true }

        ActivityScenario.launch<CloudflareSolveActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = checkNotNull(activity.window.decorView.findToolbar())
                assertTrue(toolbar.menu.performIdentifierAction(1, 0))

                val dialog = activity.continueWithoutCookieDialog()
                assertTrue(dialog.isShowing)
                assertEquals(
                    "Continue anyway",
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
                )
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SourceAccessRetryCoordinator.consumeReadyRetry()?.invoke()
            assertTrue("Confirmed no-cookie flow did not release the pending retry", retried)
        }
    }

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

private fun CloudflareSolveActivity.continueWithoutCookieDialog(): AlertDialog {
    val field = CloudflareSolveActivity::class.java.getDeclaredField("continueWithoutCookieDialog")
    field.isAccessible = true
    return checkNotNull(field.get(this) as? AlertDialog)
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
