package com.vinicius741.webnovelarchiver.feature.library

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeSelectableCardRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeThemedSpinner
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill

internal fun ScreenHost.showLibrarySelection(initialSelectedIds: Set<String> = emptySet()) {
    val stories = storage.getLibrary()
    val selectedIds = initialSelectedIds.toMutableSet()
    screen(title = "Select Novels", onBack = { showLibrary() }) {
        var refreshBulkActions: () -> Unit = {}
        // X3: select-all / deselect-all affordance.
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check) {
                selectedIds.clear()
                selectedIds.addAll(stories.map { it.id })
                showLibrarySelection(selectedIds)
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close) {
                selectedIds.clear()
                showLibrarySelection(selectedIds)
            }
        }
        addView(
            scroll(
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    // X1: card-style rows with title/author instead of bare CheckBoxes.
                    stories.forEach { story ->
                        addView(
                            makeSelectableCardRow(
                                context,
                                title = story.title,
                                subtitle = story.author,
                                selected = selectedIds.contains(story.id),
                            ) { checked ->
                                if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                                refreshBulkActions()
                            },
                        )
                    }
                },
            ),
            verticalFill(),
        )
        // X2: bulk actions docked at the bottom as full-width primary CTAs.
        lateinit var moveButton: Button
        lateinit var deleteButton: Button
        moveButton =
            fullButton("Move ${selectedIds.size} Selected", Btn.TONAL, R.drawable.wna_folder, bottomMarginDp = 8) {
                if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
            }
        deleteButton =
            fullButton("Delete Selected", Btn.ERROR, R.drawable.wna_delete, bottomMarginDp = 0) {
                if (selectedIds.isEmpty()) {
                    toast("No novels selected")
                } else {
                    confirm("Delete ${selectedIds.size} selected novels?", confirmLabel = "Delete") {
                        selectedIds.forEach { storage.deleteStory(it) }
                        showLibrary()
                    }
                }
            }
        refreshBulkActions = {
            moveButton.text = "Move ${selectedIds.size} Selected"
            deleteButton.text = if (selectedIds.isEmpty()) "Delete Selected" else "Delete ${selectedIds.size} Selected"
        }
        refreshBulkActions()
    }
}

internal fun ScreenHost.showMoveStoriesDialog(storyIds: List<String>) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options =
        tabOptions.map { (tabId, label) ->
            label to {
                storyIds.forEach { id ->
                    storage.getStory(id)?.let { story ->
                        story.tabId = tabId
                        storage.addOrUpdateStory(story)
                    }
                }
                showLibrary()
            }
        }
    val novelLabel = if (storyIds.size == 1) "Novel" else "Novels"
    showStyledOptionsDialog("Move ${storyIds.size} $novelLabel", options)
}

internal fun ScreenHost.showAddStory() {
    val tabs = storage.getTabs().sortedBy { it.order }
    // Re-renderable state: the URL the user typed and the current fetch status, captured by the
    // screen closure so they survive the re-renders we trigger as the sync progresses. Keeping the
    // flow on this screen (instead of navigating to a separate "Working" page) means the user stays
    // in context — the button flips to "Fetching..." and a spinner+status line appear beneath it.
    //
    // When invoked while idle (the FAB / empty-state CTAs all route here), treat it as a fresh open
    // and clear any leftover URL draft from a previous visit. A non-null status means we're mid-fetch
    // and this call is a status-driven re-render, so preserve both fields as-is.
    if (addStoryStatus == null) {
        addStoryUrlText = ""
    }
    val status = addStoryStatus
    screen(title = "Add Story", subtitle = "Paste a story URL to import", onBack = { showLibrary() }, scrollable = true) {
        rerender = { showAddStory() }
        val url =
            makeField(
                context,
                addStoryUrlText ?: "",
                "Royal Road or Scribble Hub story URL",
                android.text.InputType.TYPE_TEXT_VARIATION_URI,
            ).apply {
                // Roomier vertical padding than the compact field style shared with search bars/dialogs,
                // so this primary URL input is easier to tap and read.
                setPadding(context.dp(Space.MD + 2), context.dp(Space.MD), context.dp(Space.MD + 2), context.dp(Space.MD))
                // Mirror typing into the captured state so a status-driven re-render restores the
                // exact text the user entered rather than blanking the field.
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            addStoryUrlText = s?.toString().orEmpty()
                        }

                        override fun afterTextChanged(s: android.text.Editable?) {}
                    },
                )
            }
        // Paste button beside the field — a one-tap content-paste affordance that reads the system
        // clipboard instead of long-pressing the field.
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
                    if (status != null) return@setOnClickListener // Ignore while a fetch is in flight.
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
        // A1: only render the "Save to tab" section when there are tabs to choose from.
        var tabSpinner: Spinner? = null
        if (tabs.isNotEmpty()) {
            section("Save to tab")
            val tabLabels = tabs.map { it.name }
            tabSpinner = makeThemedSpinner(context, tabLabels)
            addView(tabSpinner)
        }
        // A2: the primary action is full-width for a consistent, large tap target. While a fetch is
        // in flight, the button is disabled and relabelled so the user can't kick off a second one
        // and the in-context loading state is unambiguous.
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
                // syncStory emits the first status ("Starting...") via onStatus, which re-renders
                // this screen with the button disabled — no need to set/toggle state manually here.
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
        // Inline progress block: a small spinner + the live status message from the sync engine,
        // rendered right where the user tapped so the screen never changes beneath them.
        status?.let { msg ->
            addView(makeAddStoryProgress(context, msg))
        }
        // A3: the "Or browse" Royal Road / Scribble Hub buttons were removed — they open the same
        // Browser screen the app-bar globe does, just with a preset URL. Use the Browser to browse.
    }
}

/**
 * In-place fetch status shared across the Add Story screen's re-renders. `null` = idle (no fetch in
 * flight); a non-null string is the latest status message from the sync engine and renders the
 * inline spinner + status line while blocking the Fetch button. Declared on [ScreenHost] so it
 * survives re-renders just like [com.vinicius741.webnovelarchiver.navigation.StoryOperationState].
 */
internal var ScreenHost.addStoryStatus: String?
    get() = addStoryScreenState.status
    set(value) {
        addStoryScreenState.status = value
    }

/** URL field text persisted across status-driven re-renders of the Add Story screen. */
internal var ScreenHost.addStoryUrlText: String?
    get() = addStoryScreenState.urlText
    set(value) {
        addStoryScreenState.urlText = value
    }

/** Inline spinner + status message block shown beneath the Fetch button while a fetch is in flight. */
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

internal fun ScreenHost.showMoveStoryDialog(story: Story) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options =
        tabOptions.map { (tabId, label) ->
            label to {
                story.tabId = tabId
                storage.addOrUpdateStory(story)
                showLibrary()
            }
        }
    showStyledOptionsDialog("Move Novel", options)
}
