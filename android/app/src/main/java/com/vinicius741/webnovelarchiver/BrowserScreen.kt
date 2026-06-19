package com.vinicius741.webnovelarchiver

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.WebViewSafety
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.systemBarTop
import com.vinicius741.webnovelarchiver.ui.tintedIcon

internal fun ScreenHost.showBrowser(startUrl: String) {
    val tabs = storage.getTabs().sortedBy { it.order }
    var webRef: WebView? = null
    val navigateBack = {
        val web = webRef
        if (web != null && web.canGoBack()) web.goBack() else showLibrary()
    }
    screen(
        title = "Browser",
        onBack = navigateBack,
        chrome = ScreenChrome.IMMERSIVE,
    ) {
        val web = WebView(context)
        webRef = web
        val input = makeField(context, startUrl, "Address", android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val lockIcon =
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_globe, ThemeManager.colors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(Space.SM + 2), 0, context.dp(Space.XS + 2), 0)
            }
        val addressRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = input.background
                setPadding(0, 0, context.dp(Space.MD + 2), 0)
            }
        // Strip the field's own background so it merges into the address-bar surface.
        input.background = null
        input.setPadding(0, input.paddingTop, context.dp(Space.XS), input.paddingBottom)
        addressRow.addView(
            lockIcon,
            LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addressRow.addView(input, LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                web.loadUrl(resolveUrl(input.text.toString()))
                true
            } else {
                false
            }
        }

        var currentUrl = resolveUrl(startUrl)
        val importButton =
            browserToolbarButton(R.drawable.wna_download, "Import novel") {
                if (!isNovelUrl(currentUrl)) return@browserToolbarButton
                if (tabs.isEmpty()) {
                    syncStory(currentUrl, null)
                } else {
                    showStyledOptionsDialog(
                        "Import to library",
                        tabs.map { tab -> tab.name to { syncStory(currentUrl, tab.id) } },
                    )
                }
            }
        importButton.visibility = View.GONE

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(ThemeManager.colors.elevation2)
                setPadding(context.dp(Space.SM), systemBarTop() + context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
                addView(browserToolbarButton(R.drawable.wna_arrow_back, "Back", navigateBack))
                addView(
                    addressRow,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(Space.XS)
                        marginEnd = context.dp(Space.XS)
                    },
                )
                addView(browserToolbarButton(R.drawable.wna_refresh, "Refresh") { web.reload() })
                addView(importButton)
            },
        )

        val progress =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.colors.elevation2)
                visibility = View.GONE
            }
        addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(2)))

        WebViewSafety.applyBrowserSettings(web)
        web.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (url != null) {
                        currentUrl = url
                        input.setText(url)
                    }
                    updateLockIcon(lockIcon, url)
                    importButton.visibility = if (isNovelUrl(url.orEmpty())) View.VISIBLE else View.GONE
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    if (url != null) {
                        currentUrl = url
                        input.setText(url)
                    }
                    updateLockIcon(lockIcon, url)
                    importButton.visibility = if (isNovelUrl(url.orEmpty())) View.VISIBLE else View.GONE
                }
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView?,
                    newProgress: Int,
                ) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
                }
            }
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.loadUrl(resolveUrl(startUrl))
    }
}

private fun ScreenHost.browserToolbarButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
): ImageView =
    ImageView(app).apply {
        val size = app.dp(44)
        contentDescription = description
        setImageDrawable(app.tintedIcon(icon, ThemeManager.colors.onSurface))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(app.dp(Space.SM + 2), app.dp(Space.SM + 2), app.dp(Space.SM + 2), app.dp(Space.SM + 2))
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

/** B4: swap the leading address-bar icon between a lock (https) and a globe (http/other). */
private fun updateLockIcon(
    lockIcon: android.widget.ImageView,
    url: String?,
) {
    val isSecure = url != null && url.startsWith("https://", ignoreCase = true)
    val res = if (isSecure) R.drawable.wna_check else R.drawable.wna_globe
    val tint = if (isSecure) ThemeManager.colors.tertiary else ThemeManager.colors.onSurfaceVariant
    lockIcon.setImageDrawable(lockIcon.context.tintedIcon(res, tint))
}
