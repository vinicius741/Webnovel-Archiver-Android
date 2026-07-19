package com.vinicius741.webnovelarchiver.feature.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.source.network.CloudflareCookies
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockDetector
import com.vinicius741.webnovelarchiver.source.network.SourceUserAgent
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.WebViewSafety
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import org.json.JSONArray

/**
 * Interactive, in-app WebView fallback for solving a Cloudflare challenge that the background
 * [com.vinicius741.webnovelarchiver.source.network.CloudflareBypassInterceptor] could not pass
 * unattended (e.g. an interactive Turnstile the off-screen solver times out on).
 *
 * Loads the blocked URL in a full-screen WebView that shares [android.webkit.CookieManager] with
 * OkHttp. When `cf_clearance` appears (the user completed the challenge, or an invisible/managed
 * challenge auto-cleared), the cookies are flushed and the activity finishes with [Activity.RESULT_OK];
 * the caller's pending retry (armed via [SourceAccessRetryCoordinator]) then fires on resume and
 * re-runs the original sync/download with the clearance cookie now in the shared jar.
 *
 * This is the narrow Cloudflare-challenge solver — distinct from OAuth, which stays in Custom Tabs
 * (see android/AGENTS.md). No login or credential handling happens here.
 */
class CloudflareSolveActivity : AppCompatActivity() {
    private lateinit var url: String
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var solved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        val colors = ThemeManager.colors
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = colors.background
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !ThemeManager.current.isDark

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar =
            Toolbar(this).apply {
                title = "Verify source access"
                setBackgroundColor(colors.elevation2)
                setTitleTextColor(colors.onSurface)
                setNavigationIcon(R.drawable.wna_close)
                setNavigationContentDescription("Cancel")
                setNavigationOnClickListener { finish() }
            }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
            // This activity draws edge to edge. Keep the toolbar background behind the status bar
            // while moving its controls below the clock/cutout, and keep the WebView clear of the
            // navigation/gesture area at the other edges.
            toolbar.setPadding(
                toolbar.paddingLeft,
                safeInsets.top,
                toolbar.paddingRight,
                toolbar.paddingBottom,
            )
            view.setPadding(safeInsets.left, 0, safeInsets.right, safeInsets.bottom)
            insets
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        statusText =
            TextView(this).apply {
                text = "Solving Cloudflare challenge…"
                setTextColor(colors.onSurfaceVariant)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                gravity = Gravity.CENTER
            }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Container fills the rest; the WebView is added to it below.
        val webContainer =
            FrameLayout(this).apply {
                setBackgroundColor(colors.background)
            }

        root.addView(
            webContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val web =
            WebView(this).apply {
                WebViewSafety.applyBrowserSettings(this)
                val ua = SourceUserAgent.resolved
                if (ua.isNotBlank()) settings.userAgentString = ua
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            pageUrl: String?,
                        ) {
                            // Check the actual loaded URL first (clearance is often scoped to it),
                            // then fall back to the URL we were asked to unblock.
                            checkSolved(view, pageUrl)
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            statusText?.text = "WebView could not complete the check. Try again or open the page in a browser."
                            webView = null
                            return true
                        }
                    }
            }
        webView = web
        webContainer.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Programmatic toolbar actions (the app builds all UI in code — no XML layouts/menus).
        val onSurface = colors.onSurface
        toolbar.menu
            .add(0, MENU_OPEN_BROWSER, 0, "Open in browser")
            .apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setIcon(tintedIcon(R.drawable.wna_open_external, onSurface))
            }
        toolbar.menu
            .add(0, MENU_DONE, 1, "Done")
            .apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setIcon(tintedIcon(R.drawable.wna_check, onSurface))
            }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_DONE -> {
                    CloudflareCookies.flush()
                    checkSolved(webView, webView?.url, fromDone = true)
                    true
                }
                MENU_OPEN_BROWSER -> {
                    openInBrowser()
                    true
                }
                else -> false
            }
        }

        setContentView(root)
        // Pre-emptively clear a stale clearance so a fresh grant is detectable; mirrors the solver.
        CloudflareCookies.clearClearance(url)
        web.loadUrl(url)
    }

    private fun onSolved() {
        if (solved) return
        solved = true
        CloudflareCookies.flush()
        appContainer.network.clearSourceAccess(url, keepBrowserTransport = true)
        SourceAccessRetryCoordinator.markReadyToRetry()
        statusText?.text = "Access verified — returning to the app…"
        setResult(Activity.RESULT_OK)
        // Brief delay so the user sees confirmation before the activity closes.
        webView?.postDelayed({ if (!isFinishing) finish() }, 400)
    }

    private fun checkSolved(
        view: WebView?,
        pageUrl: String?,
        fromDone: Boolean = false,
    ) {
        val effectiveUrl = pageUrl ?: url
        if (!hasClearance(effectiveUrl)) {
            statusText?.text =
                if (fromDone) {
                    "No Cloudflare clearance cookie yet. Complete the check first."
                } else {
                    "If the check doesn't clear, complete it, then tap Done."
                }
            return
        }
        view?.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
            val html = decodeJavascriptString(htmlJson)
            if (!SourceAccessBlockDetector.isChallengeHtml(html) && hasClearance(effectiveUrl)) {
                onSolved()
            } else {
                statusText?.text = "Cloudflare is still verifying this page. Complete the check, then tap Done."
            }
        } ?: run {
            statusText?.text = "Cloudflare is still verifying this page. Complete the check, then tap Done."
        }
    }

    private fun hasClearance(pageUrl: String): Boolean =
        CloudflareCookies.hasClearance(pageUrl) || CloudflareCookies.hasClearance(SCRIBBLE_HUB_BASE_URL)

    private fun decodeJavascriptString(value: String?): String = runCatching { JSONArray("[$value]").getString(0) }.getOrDefault("")

    private fun openInBrowser() {
        // Secondary escape hatch: a real browser is a supported Cloudflare environment and can solve
        // challenges the WebView cannot. (Its cookies are isolated from this app, so the in-app
        // WebView remains the primary path; this is only for stubborn cases.)
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            WebViewSafety.destroy(web)
        }
        webView = null
    }

    companion object {
        private const val EXTRA_URL = "cloudflare_solve_url"
        private const val MENU_DONE = 1
        private const val MENU_OPEN_BROWSER = 2
        private const val SCRIBBLE_HUB_BASE_URL = "https://www.scribblehub.com/"

        /** Launches the solver for [url]. The caller arms its retry via [SourceAccessRetryCoordinator]. */
        fun launch(
            context: Context,
            url: String,
        ) {
            val intent =
                Intent(context, CloudflareSolveActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                }
            context.startActivity(intent)
        }
    }
}
