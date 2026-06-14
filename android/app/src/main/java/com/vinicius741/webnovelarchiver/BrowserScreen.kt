package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.net.Uri
import android.text.InputType
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
    screen(title = "Browser", subtitle = "Browse and import novels", onBack = { showLibrary() }) {
        val input = makeField(context, startUrl, "Address", InputType.TYPE_TEXT_VARIATION_URI)
        addView(input)
        val progress = makeText(context, "Ready", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        addView(progress)
        section("Save imported novel to")
        val tabSpinner = Spinner(context)
        val tabLabels = listOf("Unassigned") + tabs.map { it.name }
        tabSpinner.adapter = ArrayAdapter(app, android.R.layout.simple_spinner_dropdown_item, tabLabels)
        if (tabs.isNotEmpty()) addView(tabSpinner)
        val activity = app
        val web = WebView(context)
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
                progress.text = "Loading..."
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) input.setText(url)
                progress.text = if (isNovelUrl(input.text.toString())) "Supported story page" else "Browse to a Royal Road fiction or Scribble Hub series page to import."
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) progress.text = "Loading $newProgress%"
            }
        }
        flow {
            button("Go", Btn.TONAL, R.drawable.wna_arrow_forward) { web.loadUrl(resolveUrl(input.text.toString())) }
            button("Back", Btn.TEXT, R.drawable.wna_arrow_back) { if (web.canGoBack()) web.goBack() else showLibrary() }
            button("Forward", Btn.TEXT, R.drawable.wna_arrow_forward) { if (web.canGoForward()) web.goForward() }
            button("Refresh", Btn.TEXT, R.drawable.wna_refresh) { web.reload() }
        }
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
            button("Home", Btn.TEXT, R.drawable.wna_list) { showLibrary() }
        }
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.loadUrl(resolveUrl(startUrl))
    }
}
