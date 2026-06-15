package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showBrowser(startUrl: String) {
    val tabs = storage.getTabs().sortedBy { it.order }
    // System/app-bar back steps through in-webview history first, then returns to the Library —
    // matching the on-screen navigation. `webRef` is populated inside the screen block below.
    var webRef: WebView? = null
    screen(title = "Browser", subtitle = "Browse and import novels", onBack = {
        val web = webRef
        if (web != null && web.canGoBack()) web.goBack() else showLibrary()
    }) {
        val activity = app
        val web = WebView(context)
        webRef = web
        // B4: address bar with a leading lock icon that reflects the current page's scheme, so https
        // pages read as secure at a glance like a real browser URL bar.
        val input = makeField(context, startUrl, "Address", android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val lockIcon = android.widget.ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_globe, ThemeManager.colors.onSurfaceVariant))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(context.dp(Space.SM + 2), 0, context.dp(Space.XS + 2), 0)
        }
        val addressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = input.background
            setPadding(0, 0, context.dp(Space.MD + 2), 0)
        }
        // Strip the field's own background so it merges into the address-bar surface.
        input.background = null
        input.setPadding(0, input.paddingTop, context.dp(Space.XS), input.paddingBottom)
        addressRow.addView(lockIcon, LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.MATCH_PARENT))
        addressRow.addView(input, LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Address bar: pressing Go/Done on the IME loads the typed URL.
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                web.loadUrl(resolveUrl(input.text.toString()))
                true
            } else false
        }
        addView(addressRow)
        // B3: the in-content "Back" button was removed — the app-bar back arrow already covers
        // webview-history-then-Library. Go / Forward / Refresh remain for in-page navigation.
        flow {
            button("Go", Btn.TONAL, R.drawable.wna_arrow_forward) { web.loadUrl(resolveUrl(input.text.toString())) }
            button("Forward", Btn.TEXT, R.drawable.wna_arrow_forward) { if (web.canGoForward()) web.goForward() }
            button("Refresh", Btn.TEXT, R.drawable.wna_refresh) { web.reload() }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val requested = request?.url?.toString() ?: return false
                if (!isGoogleAuthUrl(requested)) return false
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requested)))
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) input.setText(url)
                updateLockIcon(lockIcon, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) input.setText(url)
                updateLockIcon(lockIcon, url)
                if (!isNovelUrl(input.text.toString())) toast("Open a supported story page to import")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
            }
        }
        // WebView fills all remaining vertical space; save/import controls dock below it.
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        // Below the WebView: save target + import actions docked at the bottom.
        section("Save imported novel to")
        val tabSpinner = Spinner(context)
        val tabLabels = listOf("Unassigned") + tabs.map { it.name }
        tabSpinner.adapter = ArrayAdapter(app, android.R.layout.simple_spinner_dropdown_item, tabLabels)
        if (tabs.isNotEmpty()) addView(tabSpinner)
        flow {
            button("Import", Btn.FILLED, R.drawable.wna_download) {
                val current = input.text.toString()
                if (!isNovelUrl(current)) {
                    toast("Open a supported story page before importing.")
                } else {
                    syncStory(current, tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id)
                }
            }
            button("External", Btn.TEXT, R.drawable.wna_open_external) { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolveUrl(input.text.toString())))) }
        }
        web.loadUrl(resolveUrl(startUrl))
    }
}

/** B4: swap the leading address-bar icon between a lock (https) and a globe (http/other). */
private fun updateLockIcon(lockIcon: android.widget.ImageView, url: String?) {
    val isSecure = url != null && url.startsWith("https://", ignoreCase = true)
    val res = if (isSecure) R.drawable.wna_check else R.drawable.wna_globe
    val tint = if (isSecure) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant
    lockIcon.setImageDrawable(lockIcon.context.tintedIcon(res, tint))
}
