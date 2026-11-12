package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.TOP_BAR_HEIGHT_DP
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.TopLevelAppBar
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.appBarActionButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTopLevelAppBar
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.systemBarTop
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Library UI state that only lives as long as the activity (not saved across process death). */
internal class LibraryUiExtras {
    /** The app-bar search field is open (it also opens whenever a query is remembered). */
    var searchOpen = false

    /** Add story's "Save to tab" pick, kept across its status-driven re-renders; null = Unassigned. */
    var addStoryTabId: String? = null
    var addStoryTabChosen = false

    /** The open Sort & filter sheet, closed when the Library re-renders (its closures would act on the old screen). */
    var sortFilterSheet: WeakReference<BottomSheetHandle>? = null
}

private val libraryExtrasByActivity = WeakHashMap<AppCompatActivity, LibraryUiExtras>()

internal val ScreenHost.libraryExtras: LibraryUiExtras
    get() = libraryExtrasByActivity.getOrPut(app) { LibraryUiExtras() }

internal const val SORT_FILTER_LABEL = "Sort and filter"

/** The browsing app bar: "Library", the novel count, and search / layout / sort & filter / add. */
internal fun ScreenHost.makeLibraryAppBar(
    novelCount: Int,
    viewMode: LibraryViewMode,
    filtersActive: Boolean,
    onSortFilter: () -> Unit,
): TopLevelAppBar {
    val actions =
        buildList {
            add(
                AppBarAction(R.drawable.wna_search, "Search") {
                    libraryExtras.searchOpen = true
                    showLibrary()
                },
            )
            // The icon shows the layout a tap switches to.
            add(
                if (viewMode == LibraryViewMode.LIST) {
                    AppBarAction(R.drawable.wna_grid_view, "Cover view") { switchLibraryViewMode(viewMode.toggled) }
                } else {
                    AppBarAction(R.drawable.wna_list, "List view") { switchLibraryViewMode(viewMode.toggled) }
                },
            )
            add(AppBarAction(R.drawable.wna_tune, SORT_FILTER_LABEL) { onSortFilter() })
            // The inner screen's navigation rail has its own Add story button.
            if (app.resources.configuration.screenWidthDp < RAIL_MIN_WIDTH_DP) {
                add(AppBarAction(R.drawable.wna_add, "Add story") { showAddStory() })
            }
        }
    return makeTopLevelAppBar(
        "Library",
        LibrarySelectionPlanning.screenSubtitle(novelCount, 0),
        actions,
        dotted = if (filtersActive) setOf(SORT_FILTER_LABEL) else emptySet(),
    )
}

/** The search app bar: back closes search, the field filters live, and Sort & filter stays reachable. */
internal class LibrarySearchBar(
    val view: View,
    val field: EditText,
    val setFilterDot: (Boolean) -> Unit,
)

internal fun ScreenHost.makeLibrarySearchBar(
    query: String,
    filtersActive: Boolean,
    onClose: () -> Unit,
    onSortFilter: () -> Unit,
): LibrarySearchBar {
    val colors = ThemeManager.colors
    val field =
        EditText(app).apply {
            hint = "Search title or author"
            setText(query)
            setSelection(query.length)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setTextColor(colors.onSurface)
            setHintTextColor(colors.onSurfaceVariant)
            textSize = SEARCH_TEXT_SP
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) textCursorDrawable?.setTint(colors.primary)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) hideKeyboard(this)
                actionId == EditorInfo.IME_ACTION_SEARCH
            }
        }
    val clear =
        app.iconButton(
            R.drawable.wna_close,
            "Clear search",
            colors.onSurfaceVariant,
            com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Small,
        ) {
            field.text.clear()
            field.requestFocus()
        }
    clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
    field.addTextChangedListener(
        object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: android.text.Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        },
    )
    val pill =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val radius = dp(SEARCH_HEIGHT_DP / 2).toFloat()
            background = strokeBg(colors.elevation2, radius, colors.outline, dp(1))
            setPadding(dp(Spacing.MD + 2), 0, dp(Spacing.XS), 0)
            addView(
                ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_search, colors.onSurfaceVariant)) },
                LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(Spacing.SM + 2) },
            )
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
        }
    val tune = appBarActionButton(app, AppBarAction(R.drawable.wna_tune, SORT_FILTER_LABEL) { onSortFilter() })
    tune.dot.visibility = if (filtersActive) View.VISIBLE else View.GONE
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.background)
            minimumHeight = systemBarTop() + dp(TOP_BAR_HEIGHT_DP)
            setPadding(dp(Spacing.XS), systemBarTop() + dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS))
            addView(app.iconButton(R.drawable.wna_arrow_back, "Close search") { onClose() })
            addView(pill, LinearLayout.LayoutParams(0, dp(SEARCH_HEIGHT_DP), 1f).apply { marginStart = dp(Spacing.XS) })
            addView(tune.view)
        }
    return LibrarySearchBar(bar, field) { on -> tune.dot.visibility = if (on) View.VISIBLE else View.GONE }
}

/** Opens the keyboard on [field] once it is attached. */
internal fun ScreenHost.focusSearch(field: EditText) {
    field.post {
        field.requestFocus()
        (app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun ScreenHost.hideKeyboard(field: EditText) {
    (app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
}

/** The selection-mode app bar: close, "N selected", and Select all, on the gold container. */
internal fun ScreenHost.makeLibrarySelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
): View {
    val colors = ThemeManager.colors
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.primaryContainer)
        minimumHeight = systemBarTop() + dp(TOP_BAR_HEIGHT_DP)
        setPadding(dp(Spacing.XS), systemBarTop() + dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS))
        addView(app.iconButton(R.drawable.wna_close, "Exit selection", colors.onPrimaryContainer) { onClose() })
        addView(
            makeText(app, LibrarySelectionPlanning.title(count), Type.TITLE_LARGE, colors.onPrimaryContainer).apply {
                tag = SELECTION_TITLE_TAG
                textSize = SELECTION_TITLE_SP
                setPadding(dp(Spacing.SM), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(app.iconButton(R.drawable.wna_select_all, "Select all", colors.onPrimaryContainer) { onSelectAll() })
    }
}

/** Retitles the selection bar in place as cards are toggled. */
internal fun ScreenHost.setLibrarySelectionTitle(count: Int) {
    frame.findViewWithTag<TextView>(SELECTION_TITLE_TAG)?.text = LibrarySelectionPlanning.title(count)
}

/**
 * The row under the tabs: the current sort (opens Sort & filter), each active source/tag filter
 * with a remove action, and Clear filters (or Filter, to open the sheet).
 */
internal class LibraryActiveFilterRow(
    val view: HorizontalScrollView,
    val render: (LibraryFilterState) -> Unit,
)

internal fun ScreenHost.makeLibraryActiveFilterRow(
    onOpenSheet: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onClear: () -> Unit,
): LibraryActiveFilterRow {
    val scroll =
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(SCREEN_SIDE_PADDING_DP), dp(Spacing.SM), dp(SCREEN_SIDE_PADDING_DP), dp(Spacing.SM + 2))
        }
    scroll.addView(row)

    fun chipParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd =
                dp(Spacing.SM)
        }

    val render: (LibraryFilterState) -> Unit = { state ->
        row.removeAllViews()
        val sortIcon = if (state.sortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending
        row.addView(
            makeCountedChip(app, LibraryFiltersPlanning.sortOptionLabel(state.sortOption), null, false, sortIcon) { onOpenSheet() }.apply {
                contentDescription =
                    "Sorted by ${LibraryFiltersPlanning.sortOptionLabel(
                        state.sortOption,
                    )}, ${if (state.sortAscending) "ascending" else "descending"}"
            },
            chipParams(),
        )
        state.selectedTags.sorted().forEach { tag ->
            row.addView(removableChip(tag) { onRemoveTag(tag) }, chipParams())
        }
        if (state.selectedTags.isNotEmpty()) {
            row.addView(dashedChip("Clear filters") { onClear() }, chipParams())
        } else {
            row.addView(makeCountedChip(app, "Filter", null, false, R.drawable.wna_filter) { onOpenSheet() }, chipParams())
        }
    }
    return LibraryActiveFilterRow(scroll, render)
}

private fun ScreenHost.removableChip(
    label: String,
    onRemove: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val radius = dp(CHIP_HEIGHT_DP / 2).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(CHIP_HEIGHT_DP)
        setPadding(dp(Spacing.MD + 2), 0, dp(Spacing.SM + 2), 0)
        background = ripple(roundedBg(colors.primaryContainer, radius), radius, colors.onSurface)
        isClickable = true
        isFocusable = true
        contentDescription = "Remove filter $label"
        addView(makeText(app, label, Type.LABEL_LARGE, colors.onPrimaryContainer))
        addView(
            ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_close, colors.onPrimaryContainer)) },
            LinearLayout.LayoutParams(dp(SMALL_ICON_DP), dp(SMALL_ICON_DP)).apply { marginStart = dp(Spacing.XS + 2) },
        )
        setOnClickListener { onRemove() }
    }
}

private fun ScreenHost.dashedChip(
    label: String,
    onClick: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val radius = dp(CHIP_HEIGHT_DP / 2).toFloat()
    return makeText(app, label, Type.LABEL_LARGE, colors.onSurface).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(CHIP_HEIGHT_DP)
        setPadding(dp(Spacing.MD + 2), 0, dp(Spacing.MD + 2), 0)
        background =
            ripple(
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), colors.outline, dp(DASH_DP).toFloat(), dp(DASH_GAP_DP).toFloat())
                },
                radius,
                colors.onSurface,
            )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

private const val SELECTION_TITLE_TAG = "wna_library_selection_title"
private const val SELECTION_TITLE_SP = 20f
private const val SEARCH_TEXT_SP = 15f
private const val SEARCH_HEIGHT_DP = 46
private const val ICON_DP = 22
private const val SMALL_ICON_DP = 16
private const val CHIP_HEIGHT_DP = 36
private const val DASH_DP = 4
private const val DASH_GAP_DP = 3
