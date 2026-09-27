package com.vinicius741.webnovelarchiver.feature.browser

import android.app.Activity
import android.app.AlertDialog
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
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.CloudflareCookies
import com.vinicius741.webnovelarchiver.source.network.CloudflarePageStateDecoder
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderedPageValidator
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewRequest
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewSolver
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockDetector
import com.vinicius741.webnovelarchiver.source.network.SourceUserAgent
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Interactive, in-app WebView fallback for solving a Cloudflare challenge that the background
 * [com.vinicius741.webnovelarchiver.source.network.CloudflareBypassInterceptor] could not pass
 * unattended (e.g. an interactive Turnstile the off-screen solver times out on).
 *
 * Loads a source page in a full-screen WebView that shares [android.webkit.CookieManager] with
 * OkHttp. When the requested page's expected content loads, the activity finishes automatically.
 * Done offers a confirmed escape hatch when the page cannot be validated. Either path releases
 * the caller's pending retry (armed via [SourceAccessRetryCoordinator]) when the app resumes.
 *
 * This is the narrow Cloudflare-challenge solver — distinct from OAuth, which stays in Custom Tabs
 * (see android/AGENTS.md). No login or credential handling happens here.
 */
class CloudflareSolveActivity : AppCompatActivity() {
    private lateinit var url: String
    private lateinit var verificationUrl: String
    private var verifiesOriginalPage = true
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var continueWithoutVerificationDialog: AlertDialog? = null
    private var solved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        verificationUrl =
            if (SourceRegistry.resolve(url) != null) {
                url
            } else {
                SourceRegistry.providerForHost(Uri.parse(url).host.orEmpty())?.descriptor?.browseUrl ?: url
            }
        verifiesOriginalPage = verificationUrl == url

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
                val ua = SourceUserAgent.forUrl(verificationUrl)
                if (ua.isNotBlank()) settings.userAgentString = ua
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            pageUrl: String?,
                        ) {
                            // The loaded document must match the requested source resource.
                            checkSolved(view)
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
                    onDone()
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
        BypassEventLog.record(BypassEventCategory.CF, "solve_flow", Uri.parse(url).host, "state" to "started")
        web.loadUrl(verificationUrl)
        scheduleSolvePolling()
    }

    private fun onSolved(verified: Boolean = true) {
        if (solved) return
        solved = true
        BypassEventLog.record(
            BypassEventCategory.CF,
            "solve_flow",
            Uri.parse(url).host,
            "state" to if (verified) "verified" else "confirmed_unverified",
        )
        CloudflareCookies.flush()
        // The failed background attempt may have left a hidden WebView on the challenge page.
        // Discard it before the retry so the confirmed browser session is followed by a fresh
        // Chromium navigation instead of a renderer whose challenge request already timed out.
        CloudflareWebViewSolver.destroySessions()
        appContainer.network.clearSourceAccess(url, keepBrowserTransport = true)
        SourceAccessRetryCoordinator.markReadyToRetry()
        statusText?.text = "Retrying source access — returning to the app…"
        setResult(Activity.RESULT_OK)
        // Brief delay so the user sees confirmation before the activity closes.
        webView?.postDelayed({ if (!isFinishing) finish() }, 400)
    }

    /**
     * onPageFinished alone never fires for some source pages on some WebView builds (the load event
     * stays pending while subresources stream forever), which used to leave this screen on
     * "Solving…" even after the page had opened. The page state is therefore also polled on a
     * timer; see CloudflareWebViewSolver for the same treatment in the background renderer.
     */
    private val solvePollRunnable: Runnable =
        Runnable {
            val web = webView ?: return@Runnable
            if (solved || isFinishing || isDestroyed) return@Runnable
            checkSolved(web)
            web.postDelayed(solvePollRunnable, SOLVE_POLL_INTERVAL_MILLIS)
        }

    private fun scheduleSolvePolling() {
        webView?.postDelayed(solvePollRunnable, SOLVE_POLL_INTERVAL_MILLIS)
    }

    private fun checkSolved(
        view: WebView?,
        confirmIfNotVerified: Boolean = false,
    ) {
        val request =
            CloudflareWebViewRequest(
                url = verificationUrl,
                method = "GET",
                userAgent = SourceUserAgent.forUrl(verificationUrl),
                headers = emptyMap(),
                postData = null,
            )
        view?.evaluateJavascript(PAGE_STATE_SCRIPT) { stateJson ->
            val page = CloudflarePageStateDecoder.decode(stateJson)
            val state =
                CloudflareSolvePlanning.pageState(
                    isChallenge = SourceAccessBlockDetector.isChallengeHtml(page.html),
                    isSettled = page.readyState == "interactive" || page.readyState == "complete",
                    isRequestedResource = CloudflareRenderedPageValidator.matchesRequestedResource(request, page.documentUrl),
                    isExpectedPage = CloudflareRenderedPageValidator.isExpectedPage(request, page.documentUrl, page.html),
                )
            when (state) {
                CloudflareSolvePageState.VERIFIED -> {
                    if (verifiesOriginalPage || confirmIfNotVerified) {
                        onSolved(verified = verifiesOriginalPage)
                    } else {
                        statusText?.text = "The source page is open. Tap Done when ready to retry the original request."
                    }
                }
                CloudflareSolvePageState.CHALLENGE_ACTIVE ->
                    statusText?.text = "Cloudflare is still verifying this page. Complete the check, then tap Done."
                CloudflareSolvePageState.PAGE_UNAVAILABLE ->
                    statusText?.text = "The page is still loading. Wait for it to open, then tap Done."
                CloudflareSolvePageState.DIFFERENT_PAGE ->
                    statusText?.text = "Waiting for the source page to finish opening."
            }
            if (confirmIfNotVerified && state != CloudflareSolvePageState.VERIFIED) {
                showContinueWithoutVerificationConfirmation()
            }
        } ?: run {
            statusText?.text = "The source page is unavailable. Try again or tap Done to retry anyway."
            if (confirmIfNotVerified) showContinueWithoutVerificationConfirmation()
        }
    }

    private fun onDone() {
        checkSolved(webView, confirmIfNotVerified = true)
    }

    private fun showContinueWithoutVerificationConfirmation() {
        if (continueWithoutVerificationDialog?.isShowing == true || isFinishing) return
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle("Continue without a verified source page?")
                .setMessage(
                    "The app could not confirm that the source page opened. Continue anyway and " +
                        "retry the request? If access is still blocked, verification may be requested again.",
                ).setPositiveButton("Continue anyway") { _, _ -> onSolved(verified = false) }
                .setNegativeButton("Keep checking", null)
                .create()
        dialog.setOnDismissListener {
            if (continueWithoutVerificationDialog === dialog) continueWithoutVerificationDialog = null
        }
        continueWithoutVerificationDialog = dialog
        dialog.show()
        dialog.applyAppTheme()
    }

    private fun openInBrowser() {
        // Secondary escape hatch: a real browser is a supported Cloudflare environment and can solve
        // challenges the WebView cannot. (Its cookies are isolated from this app, so the in-app
        // WebView remains the primary path; this is only for stubborn cases.)
        BypassEventLog.record(BypassEventCategory.CF, "solve_flow", Uri.parse(url).host, "state" to "opened_external")
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(verificationUrl))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))) }
        }
    }

    override fun onDestroy() {
        continueWithoutVerificationDialog?.dismiss()
        continueWithoutVerificationDialog = null
        super.onDestroy()
        webView?.let { web ->
            web.removeCallbacks(solvePollRunnable)
            (web.parent as? ViewGroup)?.removeView(web)
            WebViewSafety.destroy(web)
        }
        webView = null
    }

    companion object {
        private const val PAGE_STATE_SCRIPT =
            "(function(){return JSON.stringify({documentUrl:location.href,readyState:document.readyState," +
                "html:document.documentElement?document.documentElement.outerHTML:''})})()"
        const val EXTRA_URL = "cloudflare_solve_url"
        private const val MENU_DONE = 1
        private const val MENU_OPEN_BROWSER = 2
        private const val SOLVE_POLL_INTERVAL_MILLIS = 1500L

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
