package com.vinicius741.webnovelarchiver.feature.library

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.browser.showBrowser
import com.vinicius741.webnovelarchiver.feature.browser.sourcePickerRows
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeThemedSpinner
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

internal fun ScreenHost.showAddStory() {
    val tabs = repository.getTabs().sortedBy { it.order }
    // URL text + fetch status live in ScreenHost state so they survive status-driven re-renders;
    // the flow stays on this screen (button flips to "Fetching...", spinner + status line appear).
    // Null status = fresh open: clear any leftover URL draft. Non-null = mid-fetch re-render.
    if (addStoryStatus == null) {
        addStoryUrlText = ""
    }
    val status = addStoryStatus
    screen(
        route = AppRoute.AddStory,
        title = "Add Story",
        subtitle = "Paste a story URL to import",
        onBack = { showLibrary() },
        scrollable = true,
    ) {
        rerender = { showAddStory() }
        val url =
            makeField(
                context,
                addStoryUrlText ?: "",
                "Story URL",
                android.text.InputType.TYPE_TEXT_VARIATION_URI,
            ).apply {
                // Roomier padding than the compact search/dialog field style — this is the
                // primary URL input.
                setPadding(context.dp(Space.MD + 2), context.dp(Space.MD), context.dp(Space.MD + 2), context.dp(Space.MD))
                // Mirror typing into state so a re-render restores the exact text, not a blank field.
                doAfterTextChanged { addStoryUrlText = it?.toString().orEmpty() }
            }
        // One-tap paste from the system clipboard, beside the field.
        val pasteButton =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val radiusPx = context.dp(ThemeManager.current.shapes.buttonRadius).toFloat()
                background =
                    ripple(
                        roundedBg(ThemeManager.colors.secondaryContainer, radiusPx),
                        radiusPx,
                        ThemeManager.colors.onSecondaryContainer,
                    )
                isClickable = true
                isFocusable = true
                setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD))
                addView(
                    ImageView(context).apply {
                        contentDescription = "Paste URL"
                        setImageDrawable(context.tintedIcon(R.drawable.wna_paste, ThemeManager.colors.onSecondaryContainer))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    },
                )
                setOnClickListener {
                    if (status != null) return@setOnClickListener
                    val clip = clipboardText()?.trim()
                    if (clip.isNullOrEmpty()) {
                        toast("Clipboard is empty")
                    } else {
                        url.setText(clip)
                        url.setSelection(clip.length)
                    }
                }
            }
        val urlRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    pasteButton,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(Space.SM)
                    },
                )
            }
        addView(urlRow)
        var tabSpinner: Spinner? = null
        if (tabs.isNotEmpty()) {
            section("Save to tab")
            val tabLabels = tabs.map { it.name }
            tabSpinner = makeThemedSpinner(context, tabLabels)
            addView(tabSpinner)
        }
        val fetching = status != null
        val fetchButton =
            fullButton(
                if (fetching) "Fetching..." else "Fetch Story",
                Btn.FILLED,
                R.drawable.wna_download,
                topMarginDp = Space.LG,
            ) {
                val spinnerPos = tabSpinner?.selectedItemPosition ?: 0
                val tabId = tabs.getOrNull(spinnerPos)?.id
                // syncStory's first onStatus re-renders with the button disabled — no manual
                // state toggling needed here.
                syncStory(
                    url.text.toString(),
                    tabId,
                    onStatus = { msg ->
                        addStoryStatus = msg
                        showAddStory()
                    },
                    onDone = { story ->
                        addStoryStatus = null
                        addStoryUrlText = null
                        showDetails(story.id)
                    },
                    onError = { error ->
                        addStoryStatus = null
                        toast(error.message ?: "Sync failed")
                        showAddStory()
                    },
                )
            }
        if (fetching) disableButton(fetchButton)
        // Spinner + live sync status rendered inline where the user tapped.
        status?.let { msg ->
            addView(makeAddStoryProgress(context, msg))
        }
        // Each row opens a Custom Tab at the source's baseUrl (sign in, browse, in-tab Import).
        section("Or browse a source")
        sourcePickerRows(context) { provider -> showBrowser(provider.baseUrl) }
            .forEach { addView(it) }
    }
}

/** Fetch status shared across re-renders: null = idle; non-null renders the inline spinner and
 *  blocks the Fetch button. */
internal var ScreenHost.addStoryStatus: String?
    get() = addStoryScreenState.status
    set(value) {
        addStoryScreenState.status = value
    }

/** URL field text persisted across status-driven re-renders. */
internal var ScreenHost.addStoryUrlText: String?
    get() = addStoryScreenState.urlText
    set(value) {
        addStoryScreenState.urlText = value
    }

private fun makeAddStoryProgress(
    context: android.content.Context,
    message: String,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, context.dp(Space.MD), 0, context.dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(
            ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                layoutParams =
                    LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply {
                        bottomMargin = context.dp(Space.SM)
                    }
            },
        )
        addView(
            makeText(context, message, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }
