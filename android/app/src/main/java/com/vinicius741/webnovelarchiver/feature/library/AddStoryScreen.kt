package com.vinicius741.webnovelarchiver.feature.library

import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeListGroup
import com.vinicius741.webnovelarchiver.ui.makeSmallSpinner
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTintedCard
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

/**
 * Add story: paste a URL (or browse a source) and fetch it into the chosen tab. The fetch stays on
 * this screen (the button flips to "Fetching...", a spinner and the status line appear). Built from
 * [AddStoryScreenModel]'s state; a new status or tab pick rebuilds the form, typing does not.
 */
internal fun ScreenHost.showAddStory() {
    screen(
        route = AppRoute.AddStory,
        title = "Add story",
        subtitle = "Paste a story URL to import",
        scrollable = true,
    ) {
        rerender = { showAddStory() }
        val model =
            screenModels.get(AppRoute.AddStory.stableKey) {
                AddStoryScreenModel({ repository.tabs.get() }, { repository.getDisplayPreferences().libraryTabId })
            }
        val state = model.refresh()
        addView(addStoryHero())
        addView(fieldLabel("Story URL"))
        val urlField = addStoryUrlField(state, model::setUrl)
        addView(urlField.first)
        val url = urlField.second
        if (state.tabs.isNotEmpty()) {
            addView(fieldLabel("Save to tab").apply { setPadding(paddingLeft, dp(Space.LG), paddingRight, paddingBottom) })
            addView(tabPickerField(state, model::chooseTab))
        }
        val fetchButton =
            fullButton(if (state.fetching) "Fetching..." else "Fetch story", Btn.FILLED, R.drawable.wna_download) {
                // syncStory's first onStatus rebuilds the form with the button disabled.
                syncStory(
                    url.text.toString(),
                    state.tabId,
                    onStatus = model::fetchStatus,
                    onDone = { story ->
                        model.fetchFinished()
                        // The finished form gives way to the story it added: Back from it goes to the Library.
                        if (navigator.current == AppRoute.AddStory) navigator.replace(AppRoute.Details(story.id))
                        showDetails(story.id)
                    },
                    onError = { error ->
                        model.fetchFailed()
                        toast(error.message ?: "Sync failed")
                    },
                )
            }
        if (state.fetching) disableButton(fetchButton)
        // Spinner + live sync status rendered inline where the user tapped.
        state.status?.let { addView(addStoryProgress(it)) }
        // Each row opens a Custom Tab at the source's baseUrl (sign in, browse, in-tab Import).
        section("Or browse a source")
        addView(
            makeListGroup(context).apply {
                sourcePickerRows(context) { provider -> showBrowser(provider.baseUrl) }.forEach { row ->
                    (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
                    addView(row)
                }
            },
        )
        bindWhileAttached(scope, model.state) { next ->
            val rebuild = next.status != state.status || next.tabId != state.tabId || next.tabs != state.tabs
            if (rebuild && navigator.current == AppRoute.AddStory) showAddStory()
        }
    }
}

private fun ScreenHost.addStoryHero(): View {
    val colors = ThemeManager.colors
    return makeTintedCard(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val box = dp(HERO_ICON_BOX_DP)
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_link, colors.onPrimaryContainer))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = roundedBg(colors.primaryContainer, dp(Space.LG).toFloat())
            },
            LinearLayout.LayoutParams(box, box).apply { marginEnd = dp(Space.MD + 2) },
        )
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(app, "Import from a link", Type.TITLE_MEDIUM, colors.onSurface))
                addView(
                    makeText(
                        app,
                        "Chapters, cover, tags and metrics are saved for offline reading.",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.SM)
                bottomMargin = dp(Space.XL - 4)
            }
    }
}

private fun ScreenHost.fieldLabel(label: String) =
    makeText(app, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(dp(Space.XS), 0, dp(Space.XS), dp(Space.SM - 2))
    }

/** The URL field: link icon, the input, and Paste. Returns the field row and its input. */
private fun ScreenHost.addStoryUrlField(
    state: AddStoryUiState,
    onChange: (String) -> Unit,
): Pair<View, EditText> {
    val colors = ThemeManager.colors
    val fetching = state.fetching
    val input =
        EditText(app).apply {
            setText(state.url)
            hint = "https://www.royalroad.com/fiction/…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            background = null
            setTextColor(colors.onSurface)
            setHintTextColor(colors.faint)
            textSize = FIELD_TEXT_SP
            setPadding(0, dp(Space.MD + 2), 0, dp(Space.MD + 2))
            contentDescription = "Story URL"
            isEnabled = !fetching
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) textCursorDrawable?.setTint(colors.primary)
            // Mirror typing into the model so a rebuild restores the exact text, not a blank field.
            doAfterTextChanged { onChange(it?.toString().orEmpty()) }
        }
    val paste =
        makeButton(app, "Paste", Btn.TEXT, R.drawable.wna_paste) {
            if (fetching) return@makeButton
            val clip = clipboardText()?.trim()
            if (clip.isNullOrEmpty()) {
                toast("Clipboard is empty")
            } else {
                input.setText(clip)
                input.setSelection(clip.length)
            }
        }.apply {
            minHeight = dp(PASTE_HEIGHT_DP)
            minimumHeight = dp(PASTE_HEIGHT_DP)
            setPadding(dp(Space.MD), 0, dp(Space.MD), 0)
            contentDescription = "Paste URL"
        }
    val row = fieldShell(R.drawable.wna_link)
    row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(paste)
    input.setOnFocusChangeListener { _, focused -> row.background = fieldBackground(focused) }
    return row to input
}

/** "Save to tab": a field that opens a sheet of tabs. */
private fun ScreenHost.tabPickerField(
    state: AddStoryUiState,
    onPick: (String?) -> Unit,
): View {
    val colors = ThemeManager.colors
    val enabled = !state.fetching
    val options = listOf<Pair<String?, String>>(null to "Unassigned") + state.tabs.map { it.id to it.name }
    val current = options.firstOrNull { it.first == state.tabId }?.second ?: "Unassigned"
    val row = fieldShell(R.drawable.wna_label)
    row.addView(
        makeText(app, current, Type.BODY_LARGE, colors.onSurface).apply {
            textSize = FIELD_TEXT_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(Space.MD + 2), 0, dp(Space.MD + 2))
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    row.addView(
        ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_chevron_down, colors.onSurfaceVariant)) },
        LinearLayout.LayoutParams(dp(FIELD_ICON_DP), dp(FIELD_ICON_DP)).apply { marginEnd = dp(Space.SM + 2) },
    )
    row.contentDescription = "Save to tab: $current"
    row.isEnabled = enabled
    row.isClickable = enabled
    row.isFocusable = enabled
    row.alpha = if (enabled) 1f else DISABLED_ALPHA
    val radius = dp(FIELD_RADIUS_DP).toFloat()
    row.background = ripple(fieldBackground(false), radius, colors.onSurface)
    if (enabled) {
        row.setOnClickListener {
            showStyledOptionsDialog(
                "Save to tab",
                options.map { (id, name) -> name to { onPick(id) } },
            )
        }
    }
    return row
}

private fun ScreenHost.fieldShell(iconRes: Int): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(FIELD_MIN_HEIGHT_DP)
        background = fieldBackground(false)
        setPadding(dp(Space.MD + 2), 0, dp(Space.XS + 2), 0)
        addView(
            ImageView(app).apply { setImageDrawable(app.tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant)) },
            LinearLayout.LayoutParams(dp(FIELD_ICON_DP), dp(FIELD_ICON_DP)).apply { marginEnd = dp(Space.SM + 2) },
        )
    }

private fun ScreenHost.fieldBackground(focused: Boolean) =
    strokeBg(
        ThemeManager.colors.elevation2,
        dp(FIELD_RADIUS_DP).toFloat(),
        if (focused) ThemeManager.colors.primary else ThemeManager.colors.outline,
        dp(1),
    )

private fun ScreenHost.addStoryProgress(message: String): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.XS), dp(Space.MD), dp(Space.XS), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(makeSmallSpinner(app, PROGRESS_SPINNER_DP))
        addView(
            makeText(app, message, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(Space.SM + 2), 0, 0, 0)
            },
        )
    }

private const val HERO_ICON_BOX_DP = 52
private const val FIELD_MIN_HEIGHT_DP = 52
private const val FIELD_RADIUS_DP = 14
private const val FIELD_ICON_DP = 22
private const val FIELD_TEXT_SP = 15f
private const val PASTE_HEIGHT_DP = 40
private const val PROGRESS_SPINNER_DP = 20
private const val DISABLED_ALPHA = 0.5f
